package com.ticketing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Per-request diagnostic timings. Inactive unless the optional performance filter starts it. */
public final class RequestTimings {
    private static final ThreadLocal<RequestTimings> CURRENT = new ThreadLocal<>();
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, Object> identity = new LinkedHashMap<>();
    private final Map<String, Double> stages = new LinkedHashMap<>();
    private final Map<String, Integer> calls = new LinkedHashMap<>();
    private String activeStage;
    private final long start = System.nanoTime();

    private RequestTimings() {}

    public static void begin() { CURRENT.set(new RequestTimings()); }

    public static void identify(String requestId, String sku) {
        RequestTimings t = CURRENT.get();
        if (t != null) { t.identity.put("requestId", requestId); t.identity.put("sku", sku); }
    }

    public static <T> T measure(String stage, Supplier<T> work) {
        RequestTimings t = CURRENT.get();
        if (t == null) return work.get();
        String parent = t.activeStage;
        t.activeStage = stage;
        long begin = System.nanoTime();
        try { return work.get(); }
        finally { record(stage, begin); t.activeStage = parent; }
    }

    public static void measure(String stage, Runnable work) {
        measure(stage, () -> { work.run(); return null; });
    }

    public static String transactionStage(String suffix) {
        RequestTimings t = CURRENT.get();
        return t == null || t.activeStage == null ? null : t.activeStage + suffix;
    }

    public static void record(String stage, long begin) {
        RequestTimings t = CURRENT.get();
        if (t != null && stage != null) {
            t.stages.merge(stage, (System.nanoTime() - begin) / 1_000_000d, Double::sum);
            t.calls.merge(stage, 1, Integer::sum);
        }
    }

    public static void finish(int status, String traceId) {
        RequestTimings t = CURRENT.get();
        CURRENT.remove();
        if (t == null) return;
        t.stages.put("http.total", (System.nanoTime() - t.start) / 1_000_000d);
        t.identity.put("traceId", traceId);
        t.identity.put("status", status);
        t.identity.put("ms", t.stages);
        t.identity.put("calls", t.calls);
        try {
            LoggerFactory.getLogger(RequestTimings.class).info("[PERF][stages] {}", JSON.writeValueAsString(t.identity));
        } catch (Exception e) {
            LoggerFactory.getLogger(RequestTimings.class).warn("Could not serialize request timings", e);
        }
    }
}
