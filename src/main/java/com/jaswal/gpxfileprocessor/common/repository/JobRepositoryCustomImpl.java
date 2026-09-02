package com.jaswal.gpxfileprocessor.common.repository;

import com.jaswal.gpxfileprocessor.common.entity.CompletionFlags;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

public class JobRepositoryCustomImpl implements JobRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public CompletionFlags markValidationCompleteAtomically(Long id) {
        return atomicFlagUpdate("validation_complete", id);
    }

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

    // column is always a hardcoded literal from the three methods above — not user input
    private CompletionFlags atomicFlagUpdate(String column, Long id) {
        Object[] row = (Object[]) em.createNativeQuery(
                "UPDATE jobs SET " + column + " = true WHERE id = :id " +
                "RETURNING validation_complete, calculations_complete, enrichment_complete"
        ).setParameter("id", id).getSingleResult();
        return new CompletionFlags((Boolean) row[0], (Boolean) row[1], (Boolean) row[2]);
    }
}
