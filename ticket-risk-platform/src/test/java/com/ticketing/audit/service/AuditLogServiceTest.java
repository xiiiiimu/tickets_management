package com.ticketing.audit.service;

import com.ticketing.audit.entity.AuditLog;
import com.ticketing.audit.mapper.AuditLogMapper;
import com.ticketing.audit.metrics.WorkflowMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogMapper auditLogMapper;
    @Mock
    private WorkflowMetrics workflowMetrics;
    @InjectMocks
    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        when(auditLogMapper.insert(any(AuditLog.class))).thenAnswer(invocation -> {
            AuditLog row = invocation.getArgument(0);
            row.setId(1L);
            return 1;
        });
    }

    @Test
    void save_llm_audit_records_metrics() {
        Map<String, Object> args = new HashMap<>();
        args.put("workflowId", "wf-1");
        args.put("nodeName", "risk_check");
        args.put("callType", "LLM");
        args.put("promptVersion", 2);
        args.put("request", "{\"role\":\"user\"}");
        args.put("response", "{\"evidenceComplete\":true}");
        args.put("tokenCount", 120);
        args.put("costTimeMs", 800);
        args.put("success", true);

        AuditLog row = auditLogService.save(args);

        assertEquals("wf-1", row.getWorkflowId());
        assertEquals("LLM", row.getCallType());
        assertEquals(120, row.getTokenCount());
        verify(workflowMetrics).recordLlm(eq("risk_check"), eq(120), eq(800L), eq(true));
    }

    @Test
    void save_truncates_large_payload() {
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 9000; i++) {
            large.append('a');
        }
        Map<String, Object> args = new HashMap<>();
        args.put("workflowId", "wf-2");
        args.put("callType", "TOOL");
        args.put("nodeName", "evaluate_fraud_signals");
        args.put("request", large.toString());
        args.put("response", "ok");
        args.put("success", true);

        AuditLog row = auditLogService.save(args);

        assertTrue(row.getRequestJson().endsWith("...[truncated]"));
        verify(workflowMetrics).recordToolAudit(eq("evaluate_fraud_signals"), eq(0L), eq(true));
    }
}
