package com.ticketing.risk;
import java.util.*;
/** Validated immutable HTTP result. Confidence is model-reported, not calibrated probability. */
public final class AgentAnalysis {
    private final AgentExecutionStatus status;
    private final String level, suggestion;
    private final int adjustment;
    private final double confidence;
    private final List<String> reasons;
    private AgentAnalysis(AgentExecutionStatus status, String level, int adjustment,
                          double confidence, List<String> reasons, String suggestion) {
        this.status=status; this.level=level; this.adjustment=adjustment;
        this.confidence=confidence; this.reasons=Collections.unmodifiableList(new ArrayList<>(reasons));
        this.suggestion=suggestion;
    }
    public static AgentAnalysis failure(AgentExecutionStatus status) {
        return new AgentAnalysis(status,"UNAVAILABLE",0,0,Collections.emptyList(),"Java rules remain authoritative");
    }
    public static AgentAnalysis validate(Map<String,Object> wire) {
        if(wire==null || !(wire.get("aiAvailable") instanceof Boolean))
            throw new IllegalArgumentException("Missing availability");
        if(Boolean.FALSE.equals(wire.get("aiAvailable"))) return failure(AgentExecutionStatus.UNAVAILABLE);
        Object level=wire.get("riskLevel"), adjustment=wire.get("scoreAdjustment"), certainty=wire.get("confidence");
        if(!Arrays.asList("LOW","MEDIUM","HIGH").contains(level) || !(adjustment instanceof Number)
                || !(certainty instanceof Number) || !(wire.get("reasons") instanceof List)
                || !(wire.get("suggestion") instanceof String)) throw new IllegalArgumentException("Invalid fields");
        double delta=((Number)adjustment).doubleValue(), confidence=((Number)certainty).doubleValue();
        if(!Double.isFinite(delta) || delta!=Math.rint(delta) || delta < -20 || delta > 20
                || !Double.isFinite(confidence) || confidence<0 || confidence>1)
            throw new IllegalArgumentException("Invalid numbers");
        List<?> raw=(List<?>)wire.get("reasons");
        if(raw.size()>20) throw new IllegalArgumentException("Too many reasons");
        List<String> reasons=new ArrayList<>();
        for(Object reason:raw) {
            if(!(reason instanceof String) || ((String)reason).trim().isEmpty())
                throw new IllegalArgumentException("Invalid reason");
            reasons.add((String)reason);
        }
        return new AgentAnalysis(AgentExecutionStatus.SUCCESS,(String)level,(int)delta,confidence,reasons,(String)wire.get("suggestion"));
    }
    public AgentExecutionStatus getStatus(){return status;}
    public Map<String,Object> toMap(){
        Map<String,Object> map=new LinkedHashMap<>();
        map.put("aiAvailable",status==AgentExecutionStatus.SUCCESS);map.put("scoreAdjustment",adjustment);
        map.put("riskLevel",level);map.put("confidence",confidence);map.put("reasons",reasons);
        map.put("suggestion",suggestion);map.put("executionStatus",status.name());
        return Collections.unmodifiableMap(map);
    }
}
