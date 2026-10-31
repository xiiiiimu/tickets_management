package com.ticketing.risk.rule;

import java.util.Map;

/**
 * Immutable evaluation context passed to every {@link RiskRuleEvaluator}.
 * First version intentionally only carries what the current threshold algorithm needs:
 * the collected feature values and whether we are in the synchronous "fast" pre-check path.
 * Do not add Order/Reservation/Db/Repository or any Spring-managed dependency here — this is
 * a pure calculation context, not a service locator.
 */
public final class RiskContext {
    private final Map<String, Double> features;
    private final boolean fast;

    public RiskContext(Map<String, Double> features, boolean fast) {
        this.features = features;
        this.fast = fast;
    }

    public Map<String, Double> getFeatures() {
        return features;
    }

    public boolean isFast() {
        return fast;
    }
}
