package com.ticketing.risk.rule;

import com.ticketing.risk.RiskRule;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RiskRuleEvaluatorRegistryTest {

    private RiskRule ruleWithFeature() {
        RiskRule r = new RiskRule();
        r.setRuleCode("X");
        r.setEnabled(true);
        Map<String, Object> config = new HashMap<>();
        config.put("feature", "f");
        config.put("threshold", 1d);
        r.setConfig(config);
        return r;
    }

    @Test void findsTheEvaluatorThatSupportsTheRule() {
        RiskRuleEvaluatorRegistry registry = new RiskRuleEvaluatorRegistry(
                Collections.singletonList(new GenericThresholdRuleEvaluator()));
        assertTrue(registry.find(ruleWithFeature()).isPresent());
    }

    @Test void unsupportedRuleReturnsEmpty() {
        RiskRuleEvaluatorRegistry registry = new RiskRuleEvaluatorRegistry(
                Collections.singletonList(new GenericThresholdRuleEvaluator()));
        RiskRule policy = new RiskRule();
        policy.setRuleCode("DECISION_POLICY");
        policy.setEnabled(true);
        policy.setConfig(new HashMap<>());
        assertFalse(registry.find(policy).isPresent());
    }

    @Test void registeringTheSameEvaluatorClassTwiceFailsFast() {
        assertThrows(IllegalStateException.class, () -> new RiskRuleEvaluatorRegistry(
                Arrays.asList(new GenericThresholdRuleEvaluator(), new GenericThresholdRuleEvaluator())));
    }

    @Test void twoEvaluatorsClaimingTheSameRuleFailFastOnLookup() {
        RiskRuleEvaluator alsoGeneric = new RiskRuleEvaluator() {
            @Override public boolean supports(RiskRule rule) { return true; }
            @Override public RuleEvaluationResult evaluate(RiskContext context, RiskRule rule) { return RuleEvaluationResult.notMatched(); }
        };
        RiskRuleEvaluatorRegistry registry = new RiskRuleEvaluatorRegistry(
                Arrays.asList(new GenericThresholdRuleEvaluator(), alsoGeneric));
        assertThrows(IllegalStateException.class, () -> registry.find(ruleWithFeature()));
    }
}
