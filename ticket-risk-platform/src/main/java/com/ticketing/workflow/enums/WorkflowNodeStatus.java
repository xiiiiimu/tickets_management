package com.ticketing.workflow.enums;

public final class WorkflowNodeStatus {
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    private WorkflowNodeStatus() {
    }
}
