package com.ticketing.workflow.service;

import com.ticketing.workflow.dto.WorkflowIdempotentResult;
import com.ticketing.workflow.entity.WorkflowRecord;
import com.ticketing.workflow.enums.WorkflowIdempotentAction;
import com.ticketing.workflow.enums.WorkflowStatus;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
public class WorkflowIdempotencyService {

    @Resource
    private WorkflowPersistenceService workflowPersistenceService;

    public WorkflowIdempotentResult resolve(String workflowId) {
        WorkflowIdempotentResult result = new WorkflowIdempotentResult();
        if (workflowId == null || workflowId.trim().isEmpty()) {
            result.setAction(WorkflowIdempotentAction.RUN);
            return result;
        }
        WorkflowRecord row = workflowPersistenceService.findWorkflow(workflowId.trim());
        if (row == null) {
            result.setAction(WorkflowIdempotentAction.RUN);
            return result;
        }
        result.setStatus(row.getStatus());
        result.setCurrentNode(row.getCurrentNode());
        if (WorkflowStatus.SUCCESS.equals(row.getStatus()) && row.getResultJson() != null) {
            result.setAction(WorkflowIdempotentAction.RETURN_RESULT);
            result.setResultJson(row.getResultJson());
            return result;
        }
        if (WorkflowStatus.RUNNING.equals(row.getStatus())
                || WorkflowStatus.PENDING.equals(row.getStatus())) {
            result.setAction(WorkflowIdempotentAction.WAIT);
            return result;
        }
        if (WorkflowStatus.INIT.equals(row.getStatus())) {
            result.setAction(WorkflowIdempotentAction.RUN);
            return result;
        }
        result.setAction(WorkflowIdempotentAction.RUN);
        return result;
    }
}
