package com.deltaforce.agentweb.service;

public class AgentApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final String requestId;
    private final Integer retryAfterSeconds;

    public AgentApiException(int status, String code, String message, String requestId, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.requestId = requestId;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getRequestId() {
        return requestId;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
