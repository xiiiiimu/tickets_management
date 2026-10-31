package com.ticketing.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hot cache for subject RiskProfile. Redis write failures must not roll back MySQL profile writes.
 */
@Component
public class RiskProfileCache {
    private static final Logger log = LoggerFactory.getLogger(RiskProfileCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> PUT_IF_NEWER =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "local old=redis.call('GET',KEYS[1]); "
                    + "if old then local p=cjson.decode(old); "
                    + "if tonumber(p.version or -1)>tonumber(ARGV[3]) then return 0 end end; "
                    + "redis.call('SET',KEYS[1],ARGV[1],'PX',ARGV[2]); return 1", Long.class);

    public RiskProfileCache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public static String key(String subjectType, String subjectValue) {
        return "risk:profile:" + subjectType.toLowerCase() + ":" + subjectValue;
    }

    public RiskProfile get(String subjectType, String subjectValue) {
        try {
            String raw = redis.opsForValue().get(key(subjectType, subjectValue));
            if (raw == null || raw.isEmpty()) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> map = json.readValue(raw, Map.class);
            RiskProfile p = new RiskProfile();
            p.setSubjectType(subjectType);
            p.setSubjectValue(subjectValue);
            p.setDecision(str(map.get("decision")));
            p.setRiskLevel(str(map.get("riskLevel")));
            if (map.get("version") instanceof Number) p.setVersion(((Number) map.get("version")).intValue());
            Object score = map.get("score");
            p.setScore(score == null ? 0 : ((Number) score).intValue());
            Object exp = map.get("expiresAt");
            if (exp instanceof Number) p.setExpiresAt(new java.sql.Timestamp(((Number) exp).longValue()));
            Object upd = map.get("updatedAt");
            if (upd instanceof Number) p.setUpdatedAt(new java.sql.Timestamp(((Number) upd).longValue()));
            return p;
        } catch (Exception e) {
            log.warn("risk profile cache read failed type={} value={}", subjectType, subjectValue, e);
            return null;
        }
    }

    public void put(RiskProfile profile) {
        if (profile == null) return;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("decision", profile.getDecision());
            body.put("riskLevel", profile.getRiskLevel());
            body.put("score", profile.getScore());
            body.put("version", profile.getVersion());
            long expiresAt = profile.getExpiresAt() == null
                    ? System.currentTimeMillis() + Duration.ofDays(1).toMillis()
                    : profile.getExpiresAt().getTime();
            body.put("expiresAt", expiresAt);
            body.put("updatedAt", System.currentTimeMillis());
            long ttlMs = Math.max(1000L, expiresAt - System.currentTimeMillis());
            // Concurrent after-commit callbacks must not replace a newer blocking portrait.
            redis.execute(PUT_IF_NEWER, java.util.Collections.singletonList(key(profile.getSubjectType(), profile.getSubjectValue())),
                    json.writeValueAsString(body), String.valueOf(ttlMs), String.valueOf(profile.getVersion()));
        } catch (Exception e) {
            log.warn("risk profile cache write failed type={} value={}; MySQL remains source of truth",
                    profile.getSubjectType(), profile.getSubjectValue(), e);
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
