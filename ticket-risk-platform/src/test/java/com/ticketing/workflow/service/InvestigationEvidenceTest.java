package com.ticketing.workflow.service;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.mockito.Mockito.*;
class InvestigationEvidenceTest {
 @Test void independentInvestigationNeverReopensCompletedFulfillment(){
  WorkflowPersistenceService persistence=mock(WorkflowPersistenceService.class);
  WorkflowExecutionService service=new WorkflowExecutionService(persistence);
  service.recordInvestigationEvidence("order","trace",Collections.singletonMap("decision","BLOCK"));
  verify(persistence).initWorkflowIfAbsent("order","trace",null,"order");
  verify(persistence).appendEvidenceNode("order","RISK_ANALYZE","trace",Collections.singletonMap("decision","BLOCK"));
  verifyNoMoreInteractions(persistence);
 }
}
