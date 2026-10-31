package com.ticketing.risk.rule;

import com.ticketing.risk.RiskRule;

/**
 * Strategy for scoring a single {@link RiskRule} against a {@link RiskContext}.
 * supports(rule) must be a purely data-driven check on the rule's own shape (e.g. "does its
 * config declare a feature to compare"), never a rule_code whitelist — that is what keeps the
 * engine open to new rule codes without code changes, and is exactly how DECISION_POLICY (which
 * has no "feature" key) stays excluded without any special-casing.
 */
public interface RiskRuleEvaluator {

    boolean supports(RiskRule rule);

    RuleEvaluationResult evaluate(RiskContext context, RiskRule rule);
}
