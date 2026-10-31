package com.ticketing.risk.rule;

import com.ticketing.risk.RiskRule;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The single data-driven threshold algorithm that today backs every scoring row in
 * tb_risk_rule (IP_FREQUENCY, DEVICE_FREQUENCY, USER_FREQUENCY, DEVICE_ACCOUNTS, IP_ACCOUNTS,
 * NEW_ACCOUNT_FREQUENCY, CANCEL_RATE, REFUND_RATE, PAYMENT_FAILURES, NEW_DEVICE_AMOUNT,
 * PAYMENT_VELOCITY as seeded in data.sql). This is a direct extraction of the loop body that
 * used to live inline in RiskRuleEngine.evaluate() — the comparison operators, getOrDefault
 * fallbacks and short-circuit order are unchanged.
 *
 * supports() only checks that the rule's config declares a "feature" to compare against — it
 * does not enumerate rule_code values. This is what lets any newly configured rule row use this
 * evaluator automatically, and what lets DECISION_POLICY (no "feature" key) fall through
 * untouched, exactly as it does today.
 */
@Component
public class GenericThresholdRuleEvaluator implements RiskRuleEvaluator {

    @Override
    public boolean supports(RiskRule rule) {
        Map<String, Object> config = rule.getConfig();
        return config != null && config.containsKey("feature");
    }

    @Override
    public RuleEvaluationResult evaluate(RiskContext context, RiskRule rule) {
        // Defensive: RiskRuleEngine already filters disabled rules before consulting the
        // registry, but this evaluator must behave correctly even when called directly.
        if (!rule.isEnabled()) {
            return RuleEvaluationResult.notMatched();
        }
        Map<String, Object> config = rule.getConfig();
        if (context.isFast() && !Boolean.TRUE.equals(config.get("fast"))) {
            return RuleEvaluationResult.notMatched();
        }
        Map<String, Double> features = context.getFeatures();
        if (config.containsKey("maxAccountAgeHours")
                && features.getOrDefault("accountAgeHours", Double.MAX_VALUE) > ((Number) config.get("maxAccountAgeHours")).doubleValue()) {
            return RuleEvaluationResult.notMatched();
        }
        if (config.containsKey("minHistory")
                && features.getOrDefault("historyCount", 0d) < ((Number) config.get("minHistory")).doubleValue()) {
            return RuleEvaluationResult.notMatched();
        }
        if (Boolean.TRUE.equals(config.get("requiresNewDevice"))
                && features.getOrDefault("newDevice", 0d) < 1) {
            return RuleEvaluationResult.notMatched();
        }
        double value = features.getOrDefault(config.get("feature").toString(), 0d);
        double threshold = ((Number) config.get("threshold")).doubleValue();
        if (value >= threshold) {
            return RuleEvaluationResult.matched(Math.max(0, rule.getWeight()), rule.getRuleCode() + "@" + rule.getVersion());
        }
        return RuleEvaluationResult.notMatched();
    }
}
