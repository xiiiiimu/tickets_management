package com.ticketing.risk.rule;

import com.ticketing.risk.RiskRule;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Looks up the {@link RiskRuleEvaluator} responsible for a given {@link RiskRule}.
 *
 * Spring collects every RiskRuleEvaluator bean into the constructor list automatically, so
 * adding a genuinely different algorithm later is just registering another @Component — no
 * factory, no chain-of-responsibility, no change to this class or to RiskRuleEngine.
 *
 * Conflict detection is intentionally simple and explicit rather than a framework: registering
 * the same evaluator class twice fails fast at startup, and two different evaluators both
 * claiming to support the same rule fails fast the first time that rule is looked up. Today,
 * with a single evaluator, neither path can trigger.
 */
@Component
public class RiskRuleEvaluatorRegistry {

    private final List<RiskRuleEvaluator> evaluators;

    public RiskRuleEvaluatorRegistry(List<RiskRuleEvaluator> evaluators) {
        Set<Class<?>> seen = new HashSet<>();
        for (RiskRuleEvaluator evaluator : evaluators) {
            if (!seen.add(evaluator.getClass())) {
                throw new IllegalStateException("Duplicate RiskRuleEvaluator registered: " + evaluator.getClass());
            }
        }
        this.evaluators = evaluators;
    }

    public Optional<RiskRuleEvaluator> find(RiskRule rule) {
        RiskRuleEvaluator match = null;
        for (RiskRuleEvaluator evaluator : evaluators) {
            if (evaluator.supports(rule)) {
                if (match != null) {
                    throw new IllegalStateException("Multiple RiskRuleEvaluator support rule " + rule.getRuleCode()
                            + ": " + match.getClass() + " and " + evaluator.getClass());
                }
                match = evaluator;
            }
        }
        return Optional.ofNullable(match);
    }
}
