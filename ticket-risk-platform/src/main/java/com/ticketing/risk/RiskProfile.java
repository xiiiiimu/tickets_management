package com.ticketing.risk;

import java.sql.Timestamp;

/**
 * Persisted subject-level risk portrait used by fast admission (not current-order gating).
 */
public class RiskProfile {
    private String id;
    private String subjectType;
    private String subjectValue;
    private String riskLevel;
    private String decision;
    private int score;
    private String reasonCodes;
    private String sourceOrderId;
    private String sourceDecisionId;
    private int version;
    private Timestamp expiresAt;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public String getSubjectValue() { return subjectValue; }
    public void setSubjectValue(String subjectValue) { this.subjectValue = subjectValue; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public String getReasonCodes() { return reasonCodes; }
    public void setReasonCodes(String reasonCodes) { this.reasonCodes = reasonCodes; }
    public String getSourceOrderId() { return sourceOrderId; }
    public void setSourceOrderId(String sourceOrderId) { this.sourceOrderId = sourceOrderId; }
    public String getSourceDecisionId() { return sourceDecisionId; }
    public void setSourceDecisionId(String sourceDecisionId) { this.sourceDecisionId = sourceDecisionId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Timestamp getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Timestamp expiresAt) { this.expiresAt = expiresAt; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }

    public boolean isExpired() {
        return expiresAt != null && expiresAt.getTime() <= System.currentTimeMillis();
    }

    public boolean isBlock() {
        return "BLOCK".equals(decision) && !isExpired();
    }
}
