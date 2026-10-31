package com.ticketing.risk;

import com.ticketing.order.TicketOrder;
import com.ticketing.repository.RiskProfileRepository;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Reads / writes subject RiskProfiles for fast admission and post-trade deep analysis.
 */
@Service
public class RiskProfileService {
    private final RiskProfileRepository profiles;
    private final RiskProfileCache cache;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private RiskSubjectFence fences;

    public RiskProfileService(RiskProfileRepository profiles, RiskProfileCache cache) {
        this.profiles = profiles;
        this.cache = cache;
    }

    /** Active BLOCK on user / device / IP blocks the current request. */
    public RiskProfile findBlocking(long userId, String deviceId, String ip) {
        for (RiskProfile p : Arrays.asList(
                resolve("USER", String.valueOf(userId)),
                resolve("DEVICE", deviceId),
                resolve("IP", ip))) {
            if (p != null && p.isBlock()) return p;
        }
        return null;
    }
    /** Enforcement always uses current MySQL versions; a stale cached PASS cannot mask a new BLOCK. */
    public RiskProfile findBlockingAuthoritative(long user,String device,String ip){
        for(RiskProfile p:profiles.findSubjects(user,device,ip)){
            if(p==null || p.isExpired())continue;
            if(p.isBlock())return p;
            // Retain only active summaries in the existing Redis cache, never whole order histories.
            cache.put(p);
        }
        return null;
    }

    public RiskProfile resolve(String subjectType, String subjectValue) {
        if (subjectValue == null || subjectValue.isEmpty()) return null;
        RiskProfile cached = cache.get(subjectType, subjectValue);
        if (cached != null) {
            if (cached.isExpired()) return null;
            return cached;
        }
        RiskProfile db = profiles.findBySubject(subjectType, subjectValue);
        if (db == null || db.isExpired()) return null;
        cache.put(db);
        return db;
    }

    /**
     * Persist portraits for user / device / IP from a deep-analysis decision.
     * Never mutates the source order; Redis failures are swallowed by the cache layer.
     */
    public void applyFromDecision(TicketOrder order, Map<String, Object> decision) {
        if(fences!=null)fences.exclusive(order.getUserId(),order.getDeviceId(),order.getIp());
        String decisionName = String.valueOf(decision.get("decision"));
        int score = decision.get("score") instanceof Number ? ((Number) decision.get("score")).intValue() : 0;
        String decisionId = decision.get("id") == null ? null : decision.get("id").toString();
        String riskLevel = riskLevel(score, decisionName);
        String reasons = decision.get("matchedRules") != null
                ? String.valueOf(decision.get("matchedRules"))
                : decisionName;
        Timestamp expiresAt = expiresAt(decisionName);
        List<String[]> subjects = Arrays.asList(
                new String[]{"USER", String.valueOf(order.getUserId())},
                new String[]{"DEVICE", order.getDeviceId()},
                new String[]{"IP", order.getIp()});
        for (String[] s : subjects) {
            if (s[1] == null || s[1].isEmpty()) continue;
            boolean written = profiles.upsertFromDecision(s[0], s[1], riskLevel, decisionName, score,
                    reasons, order.getId(), decisionId, expiresAt);
            if (written) {
                RiskProfile refreshed = profiles.findBySubject(s[0], s[1]);
                if (refreshed != null) {
                    // Publish the portrait only after its database transaction is committed.
                    if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                                new org.springframework.transaction.support.TransactionSynchronization() {
                                    @Override public void afterCommit() {
                                        RiskProfile latest = profiles.findBySubject(s[0], s[1]);
                                        if (latest != null) cache.put(latest);
                                    }
                                });
                    } else cache.put(refreshed);
                }
            }
        }
    }

    static String riskLevel(int score, String decision) {
        if ("BLOCK".equals(decision) || score >= 100) return "HIGH";
        if ("REVIEW".equals(decision) || score >= 50) return "MEDIUM";
        return "LOW";
    }

    static Timestamp expiresAt(String decision) {
        long ttl = "BLOCK".equals(decision) ? Duration.ofDays(7).toMillis()
                : "REVIEW".equals(decision) ? Duration.ofDays(1).toMillis()
                : Duration.ofHours(6).toMillis();
        return new Timestamp(System.currentTimeMillis() + ttl);
    }
}
