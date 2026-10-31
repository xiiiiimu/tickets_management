package com.ticketing.workflow.dto;

import lombok.Data;

@Data
public class WorkflowAcquireResult {

    private boolean acquired;
    private String idempotentAction;
    private String status;
    private String currentNode;
    private String cachedResultJson;
}
