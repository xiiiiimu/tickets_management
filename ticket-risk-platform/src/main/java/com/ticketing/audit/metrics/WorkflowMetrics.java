package com.ticketing.audit.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class WorkflowMetrics {

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();

    public WorkflowMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordNode(String nodeName, String agentName, boolean success, long costMs, int retryCount) {
        String node = nodeName != null ? nodeName : "unknown";
        String agent = agentName != null ? agentName : "unknown";
        meterRegistry.counter("agent.node.invoke",
                "node", node, "agent", agent, "success", String.valueOf(success)).increment();
        timer("node." + node).record(costMs, TimeUnit.MILLISECONDS);
        if (retryCount > 0) {
            meterRegistry.counter("agent.node.retry", "node", node).increment(retryCount);
        }
    }

    public void recordLlm(String nodeName, int tokenCount, long costMs, boolean success) {
        String node = nodeName != null ? nodeName : "unknown";
        meterRegistry.counter("agent.llm.invoke", "node", node, "success", String.valueOf(success)).increment();
        if (tokenCount > 0) {
            meterRegistry.counter("agent.llm.tokens", "node", node).increment(tokenCount);
        }
        timer("llm." + node).record(costMs, TimeUnit.MILLISECONDS);
    }

    public void recordToolAudit(String toolName, long costMs, boolean success) {
        String tool = toolName != null ? toolName : "unknown";
        meterRegistry.counter("agent.tool.audit", "tool", tool, "success", String.valueOf(success)).increment();
        timer("tool." + tool).record(costMs, TimeUnit.MILLISECONDS);
    }

    public void recordManualReview() {
        meterRegistry.counter("workflow.manual_review").increment();
    }

    public void recordLlmCacheHit(String nodeName) {
        meterRegistry.counter("agent.llm.cache_hit", "node", nodeName != null ? nodeName : "unknown").increment();
    }

    public void recordMq(String event, boolean success, long costMs) {
        String evt = event != null ? event : "unknown";
        meterRegistry.counter("ticketing.mq.event", "event", evt, "success", String.valueOf(success)).increment();
        timer("mq." + evt).record(costMs, TimeUnit.MILLISECONDS);
    }

    private Timer timer(String name) {
        return timers.computeIfAbsent(name, n ->
                Timer.builder("agent.workflow.duration")
                        .tag("name", n)
                        .register(meterRegistry));
    }
}
