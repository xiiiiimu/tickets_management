package com.ticketing.risk;
import java.util.*;
/** Pure extraction. Existing low-confidence and negative-adjustment semantics are preserved. */
public final class RiskAdjudicator {
    private RiskAdjudicator(){}
    public static Verdict decide(int score,RiskDecision preliminary,Map<String,Object> policy,Map<String,Object> analysis){
        int adjusted = score;
        if (Boolean.TRUE.equals(analysis.get("aiAvailable")) && analysis.get("scoreAdjustment") instanceof Number)
            adjusted += Math.max(0, Math.min(((Number) policy.get("agentMaxAdjustment")).intValue(), ((Number) analysis.get("scoreAdjustment")).intValue()));
        RiskDecision decision = preliminary == RiskDecision.BLOCK ? RiskDecision.BLOCK
                : classify(adjusted, ((Number) policy.get("review")).intValue(), ((Number) policy.get("block")).intValue());
        // Java's portrait policy: a confident, evidenced investigation can resolve a grey case.
        // This does not authorize AI to change the completed order or execute free-text suggestions.
        double confidenceFloor = policy.get("agentConfidenceThreshold") instanceof Number
                ? ((Number) policy.get("agentConfidenceThreshold")).doubleValue() : 0.8;
        if (preliminary == RiskDecision.REVIEW && Boolean.TRUE.equals(analysis.get("aiAvailable"))
                && analysis.get("confidence") instanceof Number
                && ((Number) analysis.get("confidence")).doubleValue() >= confidenceFloor
                && analysis.get("reasons") instanceof List && !((List<?>) analysis.get("reasons")).isEmpty()) {
            if ("HIGH".equals(analysis.get("riskLevel"))) {
                adjusted = Math.max(adjusted, ((Number) policy.get("block")).intValue());
                decision = RiskDecision.BLOCK;
            } else if ("LOW".equals(analysis.get("riskLevel")) && decision != RiskDecision.BLOCK) {
                adjusted = Math.min(adjusted, ((Number) policy.get("review")).intValue() - 1);
                decision = RiskDecision.PASS;
            }
        }
        return new Verdict(decision,adjusted);
    }
    private static RiskDecision classify(int score,int review,int block){
        return score>=block?RiskDecision.BLOCK:score>=review?RiskDecision.REVIEW:RiskDecision.PASS;
    }
    public static final class Verdict {
        private final RiskDecision decision; private final int score;
        private Verdict(RiskDecision decision,int score){this.decision=decision;this.score=score;}
        public RiskDecision getDecision(){return decision;} public int getScore(){return score;}
    }
}
