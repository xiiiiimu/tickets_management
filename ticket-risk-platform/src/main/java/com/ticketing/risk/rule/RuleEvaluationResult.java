package com.ticketing.risk.rule;

/**
 * Outcome of one {@link RiskRuleEvaluator#evaluate(RiskContext, com.ticketing.risk.RiskRule)} call.
 * When the rule did not match, scoreContribution is 0 and matchedTag is null; RiskRuleEngine
 * only aggregates the contribution/tag when {@link #isMatched()} is true, mirroring the original
 * inline "if threshold met { score += ...; matches.add(...) }" behaviour exactly.
 */
public final class RuleEvaluationResult {
    private final boolean matched;
    private final int scoreContribution;
    private final String matchedTag;

    private RuleEvaluationResult(boolean matched, int scoreContribution, String matchedTag) {
        this.matched = matched;
        this.scoreContribution = scoreContribution;
        this.matchedTag = matchedTag;
    }

    public static RuleEvaluationResult matched(int scoreContribution, String matchedTag) {
        return new RuleEvaluationResult(true, scoreContribution, matchedTag);
    }

    public static RuleEvaluationResult notMatched() {
        return new RuleEvaluationResult(false, 0, null);
    }

    public boolean isMatched() {
        return matched;
    }

    public int getScoreContribution() {
        return scoreContribution;
    }

    public String getMatchedTag() {
        return matchedTag;
    }
}
