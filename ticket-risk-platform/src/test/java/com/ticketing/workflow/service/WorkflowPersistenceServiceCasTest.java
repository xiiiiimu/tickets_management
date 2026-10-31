package com.ticketing.workflow.service;

import com.ticketing.workflow.entity.WorkflowRecord;
import com.ticketing.workflow.enums.WorkflowStatus;
import com.ticketing.workflow.mapper.WorkflowMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowPersistenceServiceCasTest {

    @Mock
    private WorkflowMapper workflowMapper;

    @InjectMocks
    private WorkflowPersistenceService workflowPersistenceService;

    @Test
    void tryAcquireRunning_successWhenCasUpdatesOneRow() {
        when(workflowMapper.casAcquireRunning("wf-1")).thenReturn(1);
        assertTrue(workflowPersistenceService.tryAcquireRunning("wf-1"));
    }

    @Test
    void tryAcquireRunning_failedWhenAlreadyRunning() {
        when(workflowMapper.casAcquireRunning("wf-2")).thenReturn(0);
        assertFalse(workflowPersistenceService.tryAcquireRunning("wf-2"));
    }
}
