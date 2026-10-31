package com.ticketing.workflow.stability;
import com.ticketing.workflow.service.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
class WorkflowExecutionStabilityTest {
 @Test void javaExecutionPersistsCheckpointAndNode(){WorkflowPersistenceService p=mock(WorkflowPersistenceService.class);new WorkflowExecutionService(p).record("order","trace","ISSUE",2);verify(p).initWorkflowIfAbsent("order","trace",null,"order");verify(p).saveCheckpoint(eq("order"),eq("ISSUE"),eq(2),any(),eq(0));verify(p).finishWorkflow("order","SUCCESS","trace",2,0);}
}
