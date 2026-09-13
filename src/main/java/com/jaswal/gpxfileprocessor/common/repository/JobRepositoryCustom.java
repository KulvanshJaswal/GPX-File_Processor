package com.jaswal.gpxfileprocessor.common.repository;

import com.jaswal.gpxfileprocessor.common.entity.CompletionFlags;

import java.util.Optional;

public interface JobRepositoryCustom {
    CompletionFlags markCalculationsCompleteAtomically(Long id);
    CompletionFlags markEnrichmentCompleteAtomically(Long id);
    Optional<Integer> markApiCallReservedAtomically(int count);
    void updateElevationCorrection(Long id, Double maxElevation, Double minElevation, Double elevationGain, Double elevationLoss);
    void updateWeatherData(Long id, String weatherJson);
    void updateCalculationResults(Long id, Double distanceKm, Double elevationGainM, Double elevationLossM,
                                   Double maxElevationM, Double minElevationM, Integer movingTimeSeconds,
                                   Integer totalTimeSeconds, Double paceKmPerMin, String difficulty, Double difficultyScore);
    void markJobFailed(Long id, String errorMessage);
}
