package com.ticketing.workflow.dto;

import lombok.Data;

@Data
public class WorkflowIdempotentResult {
    private String action;
    private String status;
    private String currentNode;
    private String resultJson;
}
