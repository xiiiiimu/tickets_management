package com.ticketing.risk;

import com.ticketing.risk.rule.GenericThresholdRuleEvaluator;
import com.ticketing.risk.rule.RiskRuleEvaluatorRegistry;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RiskRuleEngineTest {

    private RiskRuleEngine engine() {
        return new RiskRuleEngine(new RiskRuleEvaluatorRegistry(
                Collections.singletonList(new GenericThresholdRuleEvaluator())));
    }

    private RiskRule rule(boolean enabled, double threshold, int weight) {
        RiskRule r = new RiskRule();
        r.setRuleCode("DEVICE_FREQUENCY");
        r.setEnabled(enabled);
        r.setWeight(weight);
        r.setVersion(3);
        Map<String, Object> c = new HashMap<>();
        c.put("feature", "deviceMinute");
        c.put("threshold", threshold);
        c.put("fast", true);
        r.setConfig(c);
        return r;
    }

    @Test
    void thresholdsAndVersionAreDataDriven() {
        RiskRuleEngine e = engine();
        Map<String, Double> f = Collections.singletonMap("deviceMinute", 60d);
        RiskScore score = e.evaluate(f, Arrays.asList(rule(true, 60, 100)), false);
        assertEquals(100, score.getValue());
        assertEquals(Arrays.asList("DEVICE_FREQUENCY@3"), score.getMatchedRules());
        assertEquals(0, e.evaluate(f, Arrays.asList(rule(true, 61, 100)), false).getValue());
    }

    @Test
    void disabledRulesDoNotMatch() {
        assertEquals(0, engine().evaluate(Collections.singletonMap("deviceMinute", 1000d),
                Arrays.asList(rule(false, 60, 100)), true).getValue());
    }

    @Test
    void decisionBoundaries() {
        RiskRuleEngine e = engine();
        assertEquals(RiskDecision.PASS, e.decide(49, 50, 100));
        assertEquals(RiskDecision.REVIEW, e.decide(50, 50, 100));
        assertEquals(RiskDecision.BLOCK, e.decide(100, 50, 100));
    }

    @Test
    void newDeviceConditionIsRequired() {
        RiskRule r = rule(true, 60, 60);
        r.getConfig().put("requiresNewDevice", true);
        assertEquals(0, engine().evaluate(Collections.singletonMap("deviceMinute", 100d),
                Arrays.asList(r), false).getValue());
    }
}

