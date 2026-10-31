package com.ticketing.workflow.service;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service
public class WorkflowExecutionService {
 private final WorkflowPersistenceService persistence;
 public WorkflowExecutionService(WorkflowPersistenceService persistence){this.persistence=persistence;}
 /** Late delivery must add evidence without moving the workflow checkpoint backwards from ISSUE. */
 @Transactional public void recordReservationEvidence(String orderId,String traceId,String reservation){
  persistence.initWorkflowIfAbsent(orderId,traceId,null,orderId);
  persistence.appendEvidenceNode(orderId,"RESERVE",traceId,reservation);
  persistence.appendEvidenceNode(orderId,"ORDER_CREATED",traceId,orderId);
 }
 /** Investigation is an independent completed observation, not a reopening of fulfillment. */
 @Transactional public void recordInvestigationEvidence(String orderId,String traceId,Object evidence){
  persistence.initWorkflowIfAbsent(orderId,traceId,null,orderId);
  persistence.appendEvidenceNode(orderId,"RISK_ANALYZE",traceId,evidence);
 }
 @Transactional public void record(String orderId,String traceId,String node,Object state){
  persistence.initWorkflowIfAbsent(orderId,traceId,null,orderId);
  persistence.beginNode(orderId,node,"java",traceId,state);
  persistence.completeNode(orderId,node,traceId,"java",true,state,null,null,0,0);
  persistence.saveCheckpoint(orderId,node,state,Collections.singletonList(node),0);
  persistence.finishWorkflow(orderId,"ISSUE".equals(node)?"SUCCESS":"RUNNING",traceId,state,0);
 }
}
