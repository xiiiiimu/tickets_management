package com.ticketing.workflow.lock;

public final class WorkflowLockConstants {

    public static final String LOCK_PREFIX = "workflow:lock:";
    public static final long LOCK_TTL_SECONDS = 300L;

    private WorkflowLockConstants() {
    }
}
