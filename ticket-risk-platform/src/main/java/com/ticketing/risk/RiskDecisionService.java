package com.ticketing.risk;

import com.ticketing.infrastructure.Db;
import com.ticketing.common.BusinessException;
import com.ticketing.order.TicketOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;

/**
 * Admission: local rules / blacklist / existing RiskProfile; suspected requests still trade.
 * Investigation: after successful issue, AI updates RiskProfiles for subsequent requests.
 */
@Service
public class RiskDecisionService {
    private final Db db;
    private final RiskFeatureService features;
    private final RiskRuleEngine engine;
    private final RiskAgentClient agent;
    private final RiskProfileService profiles;
    private final ObjectMapper json;
    private final MeterRegistry metrics;

    public RiskDecisionService(Db db, RiskFeatureService features, RiskRuleEngine engine, RiskAgentClient agent,
                               RiskProfileService profiles, ObjectMapper json, MeterRegistry metrics) {
        this.db = db;
        this.features = features;
        this.engine = engine;
        this.agent = agent;
        this.profiles = profiles;
        this.json = json;
        this.metrics = metrics;
    }

    @SuppressWarnings("unchecked")
    public List<RiskRule> rules() {
        List<RiskRule> result = new ArrayList<>();
        for (Map<String, Object> row : db.jdbc.queryForList("SELECT * FROM tb_risk_rule ORDER BY rule_code")) {
            try {
                RiskRule r = new RiskRule();
                r.setRuleCode(row.get("rule_code").toString());
                r.setEnabled(Boolean.parseBoolean(row.get("enabled").toString()) || "1".equals(row.get("enabled").toString()));
                r.setWeight(((Number) row.get("weight")).intValue());
                r.setVersion(((Number) row.get("version")).intValue());
                r.setConfig(json.readValue(row.get("config").toString(), Map.class));
                result.add(r);
            } catch (Exception e) {
                throw new IllegalStateException("Invalid risk rule", e);
            }
        }
        return result;
    }

    private Map<String, Object> policy(List<RiskRule> rules) {
        return rules.stream().filter(r -> r.getRuleCode().equals("DECISION_POLICY") && r.isEnabled()).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing decision policy")).getConfig();
    }

    /**
     * Real-time admission for the current grab-ticket request.
     * Uses existing RiskProfile / blacklist / local rules — never waits for Agent.
     */
    public void fast(long user, String device, String ip) {
        admit(user, device, ip);
    }
    /** Real V2 admission, same rule engine; BLOCK is a durable auditable result before inventory. */
    public Map<String,Object> classifyReservation(long user,String device,String ip,List<RiskRule> rules,String requestId){
        Map<String,Object> p=policy(rules);
        Map<String,Double> f=features.collect(user,device,ip,true,false,0,"reservation:"+requestId);
        String reason=null;
        if(f.get("ipMinute")>=((Number)p.get("rateLimit")).doubleValue())reason="RATE_LIMITED";
        else if(features.blacklisted(user,device,ip))reason="BLACKLIST";
        else if(profiles.findBlockingAuthoritative(user,device,ip)!=null)reason="PROFILE_BLOCK";
        RiskScore score=engine.evaluate(f,rules,false);
        RiskDecision decision=reason==null?engine.decide(score.getValue(),((Number)p.get("review")).intValue(),((Number)p.get("block")).intValue()):RiskDecision.BLOCK;
        if(decision==RiskDecision.BLOCK && reason==null)reason="RULE_BLOCK";
        Map<String,Object> result=new LinkedHashMap<>();result.put("decision",decision.name());result.put("score",score.getValue());
        result.put("features",f);result.put("matchedRules",score.getMatchedRules());result.put("policy",new LinkedHashMap<>(p));
        result.put("resultCode",reason==null?decision.name():reason);
        metrics.counter("reservation.risk.decision","decision",decision.name()).increment();return result;
    }

    public Map<String, Object> admit(long user, String device, String ip) {
        return admit(user, device, ip, rules());
    }

    /** Explicit pre-sale snapshot; global behavior remains the default for unmanaged requests. */
    public Map<String, Object> admit(long user, String device, String ip, List<RiskRule> rules) {
        Map<String, Object> p = policy(rules);
        Map<String, Double> f = features.collect(user, device, ip, true, false, 0);
        if (f.get("ipMinute") >= ((Number) p.get("rateLimit")).doubleValue()) {
            metrics.counter("risk.fast.rate_limited").increment();
            metrics.counter("risk.fast.block").increment();
            throw new BusinessException(429, "请求过于频繁");
        }
        if (features.blacklisted(user, device, ip)) {
            metrics.counter("risk.fast.blacklist").increment();
            metrics.counter("risk.fast.block").increment();
            throw new BusinessException(409, "请求已被风险策略阻止");
        }
        RiskProfile blocked = profiles.findBlocking(user, device, ip);
        if (blocked != null) {
            metrics.counter("risk.fast.profile_block").increment();
            metrics.counter("risk.fast.block").increment();
            throw new BusinessException(409, "请求已被风险策略阻止");
        }
        // All local signals classify this request; REVIEW is admitted without waiting for AI.
        RiskScore score = engine.evaluate(f, rules, false);
        RiskDecision decision = engine.decide(score.getValue(), ((Number) p.get("review")).intValue(),
                ((Number) p.get("block")).intValue());
        if (decision == RiskDecision.BLOCK) {
            metrics.counter("risk.fast.block").increment();
            throw new BusinessException(409, "请求已被风险策略阻止");
        }
        metrics.counter("risk.fast.allow").increment();
        Map<String, Object> admission = new LinkedHashMap<>();
        admission.put("decision", decision.name());
        admission.put("score", score.getValue());
        admission.put("features", f);
        admission.put("matchedRules", score.getMatchedRules());
        admission.put("policy", p);
        return admission;
    }

    /** Called in the reserve transaction. Only suspected orders need a durable snapshot. */
    public void saveAdmission(String orderId, Map<String, Object> admission, String device, String ip) {
        if (admission == null || !"REVIEW".equals(admission.get("decision"))) return;
        db.jdbc.update("INSERT INTO tb_risk_decision(id,order_id,phase,decision,score,features,matched_rules,agent_analysis,context_key) VALUES(?,?,?,?,?,?,?,?,?)",
                Db.id(), orderId, "ADMISSION", "REVIEW", admission.get("score"), db.json(admission.get("features")),
                db.json(admission.get("matchedRules")), db.json(admission.get("policy")), db.json(Arrays.asList(device, ip)));
    }

    public boolean needsInvestigation(String orderId) {
        return db.one("SELECT id FROM tb_risk_decision WHERE order_id=? AND phase='ADMISSION' AND decision='REVIEW'", orderId) != null;
    }

    /** No transaction or order lock may span this method's remote AI call. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> investigate(TicketOrder order) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("AI investigation must run outside a database transaction");
        if (!"ISSUED".equals(order.getStatus())){
            if(!Arrays.asList("REFUNDING","REFUNDED").contains(order.getStatus())
                    || db.one("SELECT id FROM tb_workflow_node WHERE workflow_id=? AND node_name='ISSUE' AND status='SUCCESS' LIMIT 1",order.getId())==null)
                throw new IllegalStateException("Risk investigation requires completed issue evidence");
        }
        Map<String, Object> snapshot = db.one("SELECT * FROM tb_risk_decision WHERE order_id=? AND phase='ADMISSION' AND decision='REVIEW'", order.getId());
        if (snapshot == null) return null; // Old/duplicate messages for orders without suspicion are harmless.
        try {
            Map<String, Double> f = json.readValue(snapshot.get("features").toString(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Double>>() {});
            List<String> matches = json.readValue(snapshot.get("matched_rules").toString(), List.class);
            Map<String, Object> p = json.readValue(snapshot.get("agent_analysis").toString(), Map.class);
            return analyze(order, "POST_ISSUE", f, matches, ((Number) snapshot.get("score")).intValue(), p, RiskDecision.REVIEW);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Invalid admission snapshot", e);
        }
    }

    /** Final decision / profiles are persisted by the consumer in a short transaction. */
    public void persist(TicketOrder order, Map<String, Object> result) {
        db.jdbc.update("INSERT INTO tb_risk_decision(id,order_id,phase,decision,score,features,matched_rules,agent_analysis,context_key) VALUES(?,?,?,?,?,?,?,?,?)",
                result.get("id"), order.getId(), result.get("phase"), result.get("decision"), result.get("score"),
                db.json(result.get("features")), db.json(result.get("matchedRules")), db.json(result.get("analysis")),
                db.json(Arrays.asList(order.getDeviceId(), order.getIp())));
        profiles.applyFromDecision(order, result);
        metrics.counter("risk." + result.get("decision").toString().toLowerCase(Locale.ROOT)).increment();
    }

    /**
     * Deep post-trade analysis. Persists tb_risk_decision and updates RiskProfiles for future requests.
     * Must not be used to mutate the current order's payment / issue path.
     */
    public Map<String, Object> evaluate(TicketOrder order, String phase, String device, String ip) {
        long begin = System.nanoTime();
        try {
            List<RiskRule> rules = rules();
            Map<String, Object> p = policy(rules);
            boolean payment = phase.equals("PAYMENT");
            Map<String, Double> f = features.collect(order.getUserId(), device, ip, payment, payment,
                    payment ? order.getAmount().doubleValue() : 0);
            RiskScore score = engine.evaluate(f, rules, false);
            RiskDecision preliminary = features.blacklisted(order.getUserId(), device, ip) ? RiskDecision.BLOCK
                    : engine.decide(score.getValue(), ((Number) p.get("review")).intValue(), ((Number) p.get("block")).intValue());
            Map<String, Object> result = analyze(order, phase, f, score.getMatchedRules(), score.getValue(), p, preliminary);
            persist(order, result);
            return result;
        } finally {
            metrics.timer("risk.evaluate.latency").record(System.nanoTime() - begin, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private Map<String, Object> analyze(TicketOrder order, String phase, Map<String, Double> f, List<String> matches,
                                       int score, Map<String, Object> p, RiskDecision preliminary) {
        Map<String, Object> input = new LinkedHashMap<>();
        Map<String, Object> orderSummary = new LinkedHashMap<>();
        orderSummary.put("amount", order.getAmount());
        orderSummary.put("status", order.getStatus());
        input.put("order", orderSummary);
        input.put("features", f);
        input.put("matchedRules", matches);
        input.put("userSummary", Collections.singletonMap("historyCount", f.get("historyCount")));
        input.put("deviceSummary", Collections.singletonMap("accounts", f.get("deviceAccounts")));
        input.put("ipSummary", Collections.singletonMap("requests", f.get("ipMinute")));
        input.put("paymentSummary", Collections.singletonMap("failures", f.get("paymentFailures")));
        // Only Java REVIEW enters investigation; clear PASS/BLOCK never waits for Python.
        Map<String, Object> analysis = preliminary == RiskDecision.REVIEW ? agent.analyze(input) : AgentAnalysis.failure(AgentExecutionStatus.NOT_CALLED).toMap();
        RiskAdjudicator.Verdict verdict = RiskAdjudicator.decide(score, preliminary, p, analysis);
        String id = Db.id();
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("decision", verdict.getDecision().name());
        result.put("score", verdict.getScore());
        result.put("matchedRules", matches);
        result.put("phase", phase);
        result.put("features", f);
        result.put("analysis", analysis);
        result.put("traceId", order.getTraceId());
        return result;
    }
}
