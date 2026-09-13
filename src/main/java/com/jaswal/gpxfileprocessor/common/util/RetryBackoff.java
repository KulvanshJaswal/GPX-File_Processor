package com.jaswal.gpxfileprocessor.common.util;

public class RetryBackoff {
    public static long getDelayMillis(int attempt) {
        return switch (attempt) {
            case 0 -> 5_000L;
            case 1 -> 30_000L;
            case 2 -> 120_000L;
            default -> 600_000L;
        };
    }
}
