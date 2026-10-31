package com.ticketing.workflow.enums;

public final class WorkflowStatus {
    public static final String INIT = "INIT";
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String MANUAL_REVIEW = "MANUAL_REVIEW";

    private WorkflowStatus() {
    }
}
