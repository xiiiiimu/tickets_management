package com.ticketing.workflow.service;

import com.ticketing.workflow.dto.WorkflowIdempotentResult;
import com.ticketing.workflow.entity.WorkflowRecord;
import com.ticketing.workflow.enums.WorkflowIdempotentAction;
import com.ticketing.workflow.enums.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowIdempotencyServiceTest {

    @Mock
    private WorkflowPersistenceService workflowPersistenceService;

    @InjectMocks
    private WorkflowIdempotencyService workflowIdempotencyService;

    @Test
    void resolve_returnsRunWhenWorkflowMissing() {
        when(workflowPersistenceService.findWorkflow("wf-1")).thenReturn(null);
        WorkflowIdempotentResult result = workflowIdempotencyService.resolve("wf-1");
        assertEquals(WorkflowIdempotentAction.RUN, result.getAction());
    }

    @Test
    void resolve_returnsCachedResultWhenSuccess() {
        WorkflowRecord row = new WorkflowRecord();
        row.setStatus(WorkflowStatus.SUCCESS);
        row.setResultJson("{\"agentSuggestion\":\"SUGGEST_APPROVE\"}");
        when(workflowPersistenceService.findWorkflow("wf-2")).thenReturn(row);

        WorkflowIdempotentResult result = workflowIdempotencyService.resolve("wf-2");
        assertEquals(WorkflowIdempotentAction.RETURN_RESULT, result.getAction());
        assertEquals("{\"agentSuggestion\":\"SUGGEST_APPROVE\"}", result.getResultJson());
    }

    @Test
    void resolve_returnsWaitWhenRunning() {
        WorkflowRecord row = new WorkflowRecord();
        row.setStatus(WorkflowStatus.RUNNING);
        row.setCurrentNode("risk_check");
        when(workflowPersistenceService.findWorkflow("wf-3")).thenReturn(row);

        WorkflowIdempotentResult result = workflowIdempotencyService.resolve("wf-3");
        assertEquals(WorkflowIdempotentAction.WAIT, result.getAction());
        assertEquals("risk_check", result.getCurrentNode());
    }

    @Test
    void resolve_returnsRunWhenInit() {
        WorkflowRecord row = new WorkflowRecord();
        row.setStatus(WorkflowStatus.INIT);
        when(workflowPersistenceService.findWorkflow("wf-init")).thenReturn(row);

        WorkflowIdempotentResult result = workflowIdempotencyService.resolve("wf-init");
        assertEquals(WorkflowIdempotentAction.RUN, result.getAction());
    }
}
