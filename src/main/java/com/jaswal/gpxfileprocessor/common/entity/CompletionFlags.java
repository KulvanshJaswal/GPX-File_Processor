package com.jaswal.gpxfileprocessor.common.entity;

public record CompletionFlags(
        boolean validationComplete,
        boolean calculationsComplete,
        boolean enrichmentComplete
) {}
