package com.jaswal.gpxfileprocessor.common.repository;

import com.jaswal.gpxfileprocessor.common.entity.CompletionFlags;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public class JobRepositoryCustomImpl implements JobRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public CompletionFlags markCalculationsCompleteAtomically(Long id) {
        return atomicFlagUpdate("calculations_complete", id);
    }

    @Override
    @Transactional
    public CompletionFlags markEnrichmentCompleteAtomically(Long id) {
        return atomicFlagUpdate("enrichment_complete", id);
    }


    private CompletionFlags atomicFlagUpdate(String column, Long id) {
        Object[] row = (Object[]) em.createNativeQuery(
                "UPDATE jobs SET " + column + " = true WHERE id = :id " +
                "RETURNING validation_complete, calculations_complete, enrichment_complete"
        ).setParameter("id", id).getSingleResult();
        return new CompletionFlags((Boolean) row[0], (Boolean) row[1], (Boolean) row[2]);
    }

    @Override
    @Transactional
    public Optional<Integer> markApiCallReservedAtomically(int count) {
        // self-heals the singleton row on first use, no data.sql needed
        em.createNativeQuery(
                "INSERT INTO api_rate_limit (id, call_count, window_start) " +
                "VALUES (1, 0, TIMESTAMP '1970-01-01 00:00:00') " +
                "ON CONFLICT (id) DO NOTHING"
        ).executeUpdate();

        // OR window_start < ... lets an expired window through so SET can reset it —
        // without it, WHERE blocks the row forever once call_count hits 1000
        List<?> result = em.createNativeQuery(
                "UPDATE api_rate_limit SET " +
                "call_count = CASE WHEN window_start < NOW() - INTERVAL '24 hours' THEN :count ELSE call_count + :count END, " +
                "window_start = CASE WHEN window_start < NOW() - INTERVAL '24 hours' THEN NOW() ELSE window_start END " +
                "WHERE id = 1 AND (window_start < NOW() - INTERVAL '24 hours' OR call_count + :count <= 1000) " +
                "RETURNING call_count"
        ).setParameter("count", count).getResultList();

        if (result.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(((Number) result.get(0)).intValue());
    }

    @Override
    @Transactional
    public void updateElevationCorrection(Long id, Double maxElevation, Double minElevation, Double elevationGain, Double elevationLoss) {
        em.createNativeQuery(
                "UPDATE jobs SET max_elevation_m = :max, min_elevation_m = :min, " +
                "elevation_gain_m = :gain, elevation_loss_m = :loss WHERE id = :id"
        ).setParameter("max", maxElevation)
                .setParameter("min", minElevation)
                .setParameter("gain", elevationGain)
                .setParameter("loss", elevationLoss)
                .setParameter("id", id)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void updateWeatherData(Long id, String weatherJson) {
        em.createNativeQuery("UPDATE jobs SET weather_data = CAST(:weatherJson AS jsonb) WHERE id = :id")
                .setParameter("weatherJson", weatherJson)
                .setParameter("id", id)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void updateCalculationResults(Long id, Double distanceKm, Double elevationGainM, Double elevationLossM,
                                          Double maxElevationM, Double minElevationM, Integer movingTimeSeconds,
                                          Integer totalTimeSeconds, Double paceKmPerMin, String difficulty, Double difficultyScore) {
        em.createNativeQuery(
                "UPDATE jobs SET distance_km = :distanceKm, elevation_gain_m = :elevationGainM, " +
                "elevation_loss_m = :elevationLossM, max_elevation_m = :maxElevationM, min_elevation_m = :minElevationM, " +
                "moving_time_seconds = :movingTimeSeconds, total_time_seconds = :totalTimeSeconds, " +
                "pace_km_per_min = :paceKmPerMin, difficulty = :difficulty, difficulty_score = :difficultyScore " +
                "WHERE id = :id"
        ).setParameter("distanceKm", distanceKm)
                .setParameter("elevationGainM", elevationGainM)
                .setParameter("elevationLossM", elevationLossM)
                .setParameter("maxElevationM", maxElevationM)
                .setParameter("minElevationM", minElevationM)
                .setParameter("movingTimeSeconds", movingTimeSeconds)
                .setParameter("totalTimeSeconds", totalTimeSeconds)
                .setParameter("paceKmPerMin", paceKmPerMin)
                .setParameter("difficulty", difficulty)
                .setParameter("difficultyScore", difficultyScore)
                .setParameter("id", id)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void markJobFailed(Long id, String errorMessage) {
        em.createNativeQuery("UPDATE jobs SET status = 'FAILED', error_message = :errorMessage WHERE id = :id"
        ).setParameter("errorMessage", errorMessage).setParameter("id", id).executeUpdate();
    }
}
