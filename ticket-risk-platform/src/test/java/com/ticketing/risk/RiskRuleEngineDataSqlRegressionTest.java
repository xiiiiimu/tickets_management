package com.ticketing.risk;

import com.ticketing.risk.rule.GenericThresholdRuleEvaluator;
import com.ticketing.risk.rule.RiskRuleEvaluatorRegistry;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden-case regression suite for the RiskRuleEngine + GenericThresholdRuleEvaluator +
 * RiskRuleEvaluatorRegistry pipeline, built directly from the actual seed data in
 * src/main/resources/data.sql (the 11 scoring rules plus the non-scoring DECISION_POLICY row).
 *
 * These expectations are hand-derived from the documented algorithm (see MIGRATION notes /
 * source audit), not from running a second copy of the pre-refactor engine — there is no
 * "old" implementation kept around anywhere in production code for comparison.
 */
class RiskRuleEngineDataSqlRegressionTest {

    private RiskRuleEngine engine() {
        return new RiskRuleEngine(new RiskRuleEvaluatorRegistry(
                Collections.singletonList(new GenericThresholdRuleEvaluator())));
    }

    private RiskRule rule(String code, int weight, Map<String, Object> config) {
        RiskRule r = new RiskRule();
        r.setRuleCode(code);
        r.setEnabled(true);
        r.setWeight(weight);
        r.setVersion(1);
        r.setConfig(config);
        return r;
    }

    private Map<String, Object> cfg(Object... kv) {
        Map<String, Object> c = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) c.put((String) kv[i], kv[i + 1]);
        return c;
    }

    /** The 11 scoring rules + DECISION_POLICY, in the same order data.sql inserts them. */
    private List<RiskRule> seededRules() {
        return Arrays.asList(
                rule("IP_FREQUENCY", 100, cfg("feature", "ipMinute", "threshold", 120d, "fast", true)),
                rule("DEVICE_FREQUENCY", 100, cfg("feature", "deviceMinute", "threshold", 60d, "fast", true)),
                rule("USER_FREQUENCY", 100, cfg("feature", "userMinute", "threshold", 30d, "fast", true)),
                rule("DEVICE_ACCOUNTS", 60, cfg("feature", "deviceAccounts", "threshold", 5d, "fast", false)),
                rule("IP_ACCOUNTS", 40, cfg("feature", "ipAccounts", "threshold", 20d, "fast", false)),
                rule("NEW_ACCOUNT_FREQUENCY", 60, cfg("feature", "userTenMinutes", "threshold", 10d, "fast", false, "maxAccountAgeHours", 24d)),
                rule("CANCEL_RATE", 50, cfg("feature", "cancelRate", "threshold", 0.6d, "fast", false, "minHistory", 5d)),
                rule("REFUND_RATE", 50, cfg("feature", "refundRate", "threshold", 0.5d, "fast", false, "minHistory", 5d)),
                rule("PAYMENT_FAILURES", 60, cfg("feature", "paymentFailures", "threshold", 3d, "fast", false)),
                rule("NEW_DEVICE_AMOUNT", 60, cfg("feature", "paymentAmount", "threshold", 1000d, "fast", false, "requiresNewDevice", true)),
                rule("PAYMENT_VELOCITY", 60, cfg("feature", "paymentMinute", "threshold", 10d, "fast", false)),
                rule("DECISION_POLICY", 0, cfg("review", 50, "block", 100, "agentMaxAdjustment", 20, "rateLimit", 180))
        );
    }

    @Test void quietTrafficScoresZero() {
        Map<String, Double> features = new HashMap<>();
        features.put("ipMinute", 0d); features.put("deviceMinute", 0d); features.put("userMinute", 0d);
        features.put("deviceAccounts", 0d); features.put("ipAccounts", 0d); features.put("userTenMinutes", 0d);
        features.put("cancelRate", 0d); features.put("refundRate", 0d); features.put("paymentFailures", 0d);
        features.put("paymentAmount", 0d); features.put("newDevice", 0d);
        features.put("accountAgeHours", 100d); features.put("historyCount", 10d);

        RiskScore score = engine().evaluate(features, seededRules(), false);
        assertEquals(0, score.getValue());
        assertTrue(score.getMatchedRules().isEmpty());
    }

    @Test void fastPathOnlyEvaluatesFastTaggedRules() {
        Map<String, Double> features = new HashMap<>();
        features.put("ipMinute", 130d);          // >= 120, fast:true -> matches
        features.put("deviceMinute", 50d);       // < 60 -> no match
        features.put("userMinute", 10d);         // < 30 -> no match
        features.put("deviceAccounts", 999d);    // would match at threshold 5, but fast:false -> must be skipped in fast mode

        RiskScore score = engine().evaluate(features, seededRules(), true);
        assertEquals(100, score.getValue());
        assertEquals(Arrays.asList("IP_FREQUENCY@1"), score.getMatchedRules());
    }

    @Test void fastPathWithNothingOverThresholdScoresZero() {
        Map<String, Double> features = new HashMap<>();
        features.put("ipMinute", 5d); features.put("deviceMinute", 5d); features.put("userMinute", 5d);
        features.put("deviceAccounts", 1000d); // fast:false, must not be considered in fast mode regardless of value

        RiskScore score = engine().evaluate(features, seededRules(), true);
        assertEquals(0, score.getValue());
        assertTrue(score.getMatchedRules().isEmpty());
    }

    @Test void deepEvaluationAggregatesMultipleMatchesAndSkipsGatedAndPolicyRows() {
        Map<String, Double> features = new HashMap<>();
        features.put("ipMinute", 10d);            // below 120
        features.put("deviceMinute", 5d);         // below 60
        features.put("userMinute", 2d);           // below 30
        features.put("deviceAccounts", 6d);       // >= 5 -> DEVICE_ACCOUNTS matches (60)
        features.put("ipAccounts", 25d);          // >= 20 -> IP_ACCOUNTS matches (40)
        features.put("userTenMinutes", 15d);      // >= 10, but accountAgeHours gate fails below
        features.put("accountAgeHours", 48d);     // > 24 -> NEW_ACCOUNT_FREQUENCY gated out despite value
        features.put("cancelRate", 0.7d);         // >= 0.6, historyCount gate satisfied -> CANCEL_RATE matches (50)
        features.put("refundRate", 0.4d);         // < 0.5 -> REFUND_RATE does not match
        features.put("historyCount", 5d);         // satisfies minHistory:5 for both CANCEL_RATE and REFUND_RATE
        features.put("paymentFailures", 5d);      // >= 3 -> PAYMENT_FAILURES matches (60)
        features.put("paymentAmount", 1500d);     // >= 1000, requiresNewDevice satisfied below -> NEW_DEVICE_AMOUNT matches (60)
        features.put("newDevice", 1d);
        features.put("paymentMinute", 12d);       // >= 10 -> PAYMENT_VELOCITY matches (60)

        RiskRuleEngine engine = engine();
        RiskScore score = engine.evaluate(features, seededRules(), false);

        assertEquals(330, score.getValue()); // 60+40+50+60+60+60
        assertEquals(Arrays.asList(
                "DEVICE_ACCOUNTS@1", "IP_ACCOUNTS@1", "CANCEL_RATE@1",
                "PAYMENT_FAILURES@1", "NEW_DEVICE_AMOUNT@1", "PAYMENT_VELOCITY@1"
        ), score.getMatchedRules());

        // DECISION_POLICY's own review/block thresholds are unaffected by its exclusion from scoring.
        assertEquals(RiskDecision.BLOCK, engine.decide(score.getValue(), 50, 100));
    }
}
