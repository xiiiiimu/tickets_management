package com.ticketing.risk;

import com.ticketing.risk.rule.RiskContext;
import com.ticketing.risk.rule.RiskRuleEvaluator;
import com.ticketing.risk.rule.RiskRuleEvaluatorRegistry;
import com.ticketing.risk.rule.RuleEvaluationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Orchestrates rule evaluation and holds the PASS/REVIEW/BLOCK boundary helper.
 * The per-rule scoring algorithm itself now lives in the com.ticketing.risk.rule package
 * (RiskRuleEvaluator implementations looked up through RiskRuleEvaluatorRegistry); this class
 * is only responsible for: building the shared RiskContext, iterating enabled rules, asking the
 * registry which evaluator (if any) applies, and aggregating score / matchedRules.
 *
 * Public method signatures are unchanged so RiskDecisionService requires no changes.
 */
@Component
public class RiskRuleEngine {

    private final RiskRuleEvaluatorRegistry registry;

    /**
     * Single constructor used by Spring (auto-wired) and by unit tests.
     * Tests must pass an explicit {@link RiskRuleEvaluatorRegistry} — there is no no-arg
     * shortcut that silently builds a second registry, so production and test wiring stay aligned.
     */
    public RiskRuleEngine(RiskRuleEvaluatorRegistry registry) {
        this.registry = registry;
    }

    public RiskScore evaluate(Map<String, Double> features, List<RiskRule> rules, boolean fast) {
        RiskContext context = new RiskContext(features, fast);
        int score = 0;
        List<String> matches = new ArrayList<>();
        for (RiskRule rule : rules) {
            if (!rule.isEnabled()) {
                continue;
            }
            Optional<RiskRuleEvaluator> evaluator = registry.find(rule);
            if (!evaluator.isPresent()) {
                continue;
            }
            RuleEvaluationResult result = evaluator.get().evaluate(context, rule);
            if (result.isMatched()) {
                score += result.getScoreContribution();
                matches.add(result.getMatchedTag());
            }
        }
        RiskScore result = new RiskScore();
        result.setValue(score);
        result.setMatchedRules(matches);
        return result;
    }

    public RiskDecision decide(int score, int review, int block) {
        return score >= block ? RiskDecision.BLOCK : score >= review ? RiskDecision.REVIEW : RiskDecision.PASS;
    }
}
