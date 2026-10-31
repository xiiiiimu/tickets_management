package com.ticketing.repository;

import com.ticketing.risk.RiskProfile;

import java.sql.Timestamp;

/**
 * JDBC access for {@code tb_risk_profile} subject portraits.
 */
public interface RiskProfileRepository {

    RiskProfile findBySubject(String subjectType, String subjectValue);
    default java.util.List<RiskProfile> findSubjects(long user,String device,String ip){
        return java.util.Arrays.asList(findBySubject("USER",Long.toString(user)),findBySubject("DEVICE",device),findBySubject("IP",ip));
    }

    /** Insert or versioned replace; returns true if a row was written. */
    boolean upsert(RiskProfile profile);

    /**
     * Conditional update when existing version matches, or insert when absent.
     * Skips when {@code sourceDecisionId} was already applied (idempotent replay).
     */
    boolean upsertFromDecision(String subjectType, String subjectValue, String riskLevel, String decision,
                               int score, String reasonCodes, String sourceOrderId, String sourceDecisionId,
                               Timestamp expiresAt);
}
