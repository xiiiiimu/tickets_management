package com.ticketing.common.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheClientJitterTest {

    @Test
    void applyJitter_withinTenPercent() {
        long base = CacheClient.applyJitter(10L, TimeUnit.MINUTES);
        assertTrue(base >= 540 && base <= 660, "ttl seconds=" + base);
    }
}
