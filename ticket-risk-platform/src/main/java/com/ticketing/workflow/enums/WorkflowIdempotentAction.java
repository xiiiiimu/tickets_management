package com.ticketing.workflow.enums;

public final class WorkflowIdempotentAction {
    public static final String RUN = "RUN";
    public static final String RETURN_RESULT = "RETURN_RESULT";
    public static final String WAIT = "WAIT";

    private WorkflowIdempotentAction() {
    }
}
