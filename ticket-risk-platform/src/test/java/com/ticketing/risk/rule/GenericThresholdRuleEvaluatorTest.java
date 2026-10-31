package com.ticketing.risk.rule;

import com.ticketing.risk.RiskRule;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GenericThresholdRuleEvaluatorTest {

    private final GenericThresholdRuleEvaluator evaluator = new GenericThresholdRuleEvaluator();

    private RiskRule rule(boolean enabled, int weight, int version, Map<String, Object> config) {
        RiskRule r = new RiskRule();
        r.setRuleCode("SAMPLE");
        r.setEnabled(enabled);
        r.setWeight(weight);
        r.setVersion(version);
        r.setConfig(config);
        return r;
    }

    private Map<String, Object> config(Object... kv) {
        Map<String, Object> c = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) c.put((String) kv[i], kv[i + 1]);
        return c;
    }

    private RiskContext context(boolean fast, Map<String, Double> features) {
        return new RiskContext(features, fast);
    }

    // 1. disabled rule
    @Test void disabledRuleNeverMatchesEvenIfThresholdMet() {
        RiskRule r = rule(false, 100, 1, config("feature", "f", "threshold", 10d));
        RuleEvaluationResult result = evaluator.evaluate(context(false, Collections.singletonMap("f", 100d)), r);
        assertFalse(result.isMatched());
        assertEquals(0, result.getScoreContribution());
    }

    // 2. config without feature is not supported (this is how DECISION_POLICY-shaped rows are excluded)
    @Test void configWithoutFeatureIsNotSupported() {
        RiskRule r = rule(true, 0, 1, config("review", 50, "block", 100));
        assertFalse(evaluator.supports(r));
    }

    // 3. normal threshold hit
    @Test void normalThresholdHitMatches() {
        RiskRule r = rule(true, 60, 2, config("feature", "f", "threshold", 10d));
        RuleEvaluationResult result = evaluator.evaluate(context(false, Collections.singletonMap("f", 15d)), r);
        assertTrue(result.isMatched());
        assertEquals(60, result.getScoreContribution());
        assertEquals("SAMPLE@2", result.getMatchedTag());
    }

    // 4. threshold miss
    @Test void belowThresholdDoesNotMatch() {
        RiskRule r = rule(true, 60, 1, config("feature", "f", "threshold", 10d));
        RuleEvaluationResult result = evaluator.evaluate(context(false, Collections.singletonMap("f", 9.99d)), r);
        assertFalse(result.isMatched());
    }

    // 5. threshold == feature value (comparison is >=, boundary must match)
    @Test void thresholdEqualToFeatureValueMatches() {
        RiskRule r = rule(true, 60, 1, config("feature", "f", "threshold", 10d));
        RuleEvaluationResult result = evaluator.evaluate(context(false, Collections.singletonMap("f", 10d)), r);
        assertTrue(result.isMatched());
    }

    // 6. fast=true context + rule not marked fast -> skipped
    @Test void fastContextSkipsNonFastRule() {
        RiskRule r = rule(true, 60, 1, config("feature", "f", "threshold", 10d));
        RuleEvaluationResult result = evaluator.evaluate(context(true, Collections.singletonMap("f", 100d)), r);
        assertFalse(result.isMatched());
    }

    // 7. fast=true context + rule marked fast -> normal evaluation proceeds
    @Test void fastContextEvaluatesFastTaggedRule() {
        RiskRule r = rule(true, 60, 1, config("feature", "f", "threshold", 10d, "fast", true));
        RuleEvaluationResult result = evaluator.evaluate(context(true, Collections.singletonMap("f", 100d)), r);
        assertTrue(result.isMatched());
    }

    // 8. maxAccountAgeHours gate
    @Test void maxAccountAgeHoursPassAndFail() {
        RiskRule r = rule(true, 60, 1, config("feature", "f", "threshold", 1d, "maxAccountAgeHours", 24d));
        Map<String, Double> withinAge = new HashMap<>(); withinAge.put("f", 5d); withinAge.put("accountAgeHours", 12d);
        assertTrue(evaluator.evaluate(context(false, withinAge), r).isMatched());
        Map<String, Double> tooOld = new HashMap<>(); tooOld.put("f", 5d); tooOld.put("accountAgeHours", 48d);
        assertFalse(evaluator.evaluate(context(false, tooOld), r).isMatched());
    }

    // 9. minHistory gate
    @Test void minHistoryPassAndFail() {
        RiskRule r = rule(true, 60, 1, config("feature", "f", "threshold", 0.5d, "minHistory", 5d));
        Map<String, Double> enoughHistory = new HashMap<>(); enoughHistory.put("f", 0.6d); enoughHistory.put("historyCount", 5d);
        assertTrue(evaluator.evaluate(context(false, enoughHistory), r).isMatched());
        Map<String, Double> notEnoughHistory = new HashMap<>(); notEnoughHistory.put("f", 0.6d); notEnoughHistory.put("historyCount", 4d);
        assertFalse(evaluator.evaluate(context(false, notEnoughHistory), r).isMatched());
    }

    // 10. requiresNewDevice gate
    @Test void requiresNewDevicePassAndFail() {
        RiskRule r = rule(true, 60, 1, config("feature", "f", "threshold", 100d, "requiresNewDevice", true));
        Map<String, Double> newDevice = new HashMap<>(); newDevice.put("f", 200d); newDevice.put("newDevice", 1d);
        assertTrue(evaluator.evaluate(context(false, newDevice), r).isMatched());
        Map<String, Double> knownDevice = new HashMap<>(); knownDevice.put("f", 200d); knownDevice.put("newDevice", 0d);
        assertFalse(evaluator.evaluate(context(false, knownDevice), r).isMatched());
    }

    // 11. negative weight is clamped to zero contribution, even though the rule still matches
    @Test void negativeWeightContributesZero() {
        RiskRule r = rule(true, -50, 1, config("feature", "f", "threshold", 1d));
        RuleEvaluationResult result = evaluator.evaluate(context(false, Collections.singletonMap("f", 10d)), r);
        assertTrue(result.isMatched());
        assertEquals(0, result.getScoreContribution());
        assertEquals("SAMPLE@1", result.getMatchedTag());
    }

    // 12. matched tag format is exactly ruleCode@version
    @Test void matchedTagIsExactlyRuleCodeAtVersion() {
        RiskRule r = rule(true, 10, 7, config("feature", "f", "threshold", 1d));
        r.setRuleCode("IP_FREQUENCY");
        RuleEvaluationResult result = evaluator.evaluate(context(false, Collections.singletonMap("f", 5d)), r);
        assertEquals("IP_FREQUENCY@7", result.getMatchedTag());
    }

    // 13. DECISION_POLICY is not picked up by this evaluator
    @Test void decisionPolicyRowIsNotSupported() {
        RiskRule policy = rule(true, 0, 1, config("review", 50, "block", 100, "agentMaxAdjustment", 20, "rateLimit", 180));
        policy.setRuleCode("DECISION_POLICY");
        assertFalse(evaluator.supports(policy));
    }
}
