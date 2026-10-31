package com.ticketing.workflow.trace;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WorkflowTraceLogger {

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_WORKFLOW_ID = "workflowId";
    public static final String MDC_NODE_NAME = "nodeName";
    public static final String MDC_AGENT_NAME = "agentName";
    public static final String MDC_RETRY_COUNT = "retryCount";
    public static final String MDC_COST_TIME = "costTime";

    public void info(String workflowId, String traceId, String nodeName, String agentName,
                     int retryCount, long costTimeMs, String message) {
        withContext(workflowId, traceId, nodeName, agentName, retryCount, costTimeMs, () ->
                log.info("[workflow-trace] {}", message));
    }

    public void error(String workflowId, String traceId, String nodeName, String agentName,
                      int retryCount, long costTimeMs, String errorCode, String errorMsg, Throwable t) {
        withContext(workflowId, traceId, nodeName, agentName, retryCount, costTimeMs, () ->
                log.error("[workflow-trace] error_code={} error_msg={}", errorCode, errorMsg, t));
    }

    private void withContext(String workflowId, String traceId, String nodeName, String agentName,
                           int retryCount, long costTimeMs, Runnable runnable) {
        String prevTrace = MDC.get(MDC_TRACE_ID);
        String prevWorkflow = MDC.get(MDC_WORKFLOW_ID);
        String prevNode = MDC.get(MDC_NODE_NAME);
        String prevAgent = MDC.get(MDC_AGENT_NAME);
        String prevRetry = MDC.get(MDC_RETRY_COUNT);
        String prevCost = MDC.get(MDC_COST_TIME);
        try {
            if (traceId != null) {
                MDC.put(MDC_TRACE_ID, traceId);
            }
            if (workflowId != null) {
                MDC.put(MDC_WORKFLOW_ID, workflowId);
            }
            if (nodeName != null) {
                MDC.put(MDC_NODE_NAME, nodeName);
            }
            if (agentName != null) {
                MDC.put(MDC_AGENT_NAME, agentName);
            }
            MDC.put(MDC_RETRY_COUNT, String.valueOf(retryCount));
            MDC.put(MDC_COST_TIME, String.valueOf(costTimeMs));
            runnable.run();
        } finally {
            restore(MDC_TRACE_ID, prevTrace);
            restore(MDC_WORKFLOW_ID, prevWorkflow);
            restore(MDC_NODE_NAME, prevNode);
            restore(MDC_AGENT_NAME, prevAgent);
            restore(MDC_RETRY_COUNT, prevRetry);
            restore(MDC_COST_TIME, prevCost);
        }
    }

    private void restore(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
