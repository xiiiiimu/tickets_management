package com.ticketing.risk;
/** Execution outcome is independent of the business decision. */
public enum AgentExecutionStatus {
    SUCCESS, NOT_CALLED, NOT_CONFIGURED, UNAVAILABLE, TIMEOUT,
    CONNECT_ERROR, HTTP_ERROR, INVALID_RESPONSE, ERROR
}
