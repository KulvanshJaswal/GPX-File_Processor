package com.jaswal.gpxfileprocessor.common.exception;

public class ApiRateLimitExceededException extends RuntimeException {
    public ApiRateLimitExceededException(String message) {
        super(message);
    }
}
