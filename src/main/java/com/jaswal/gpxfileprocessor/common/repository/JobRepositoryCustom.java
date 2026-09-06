package com.jaswal.gpxfileprocessor.common.repository;

import com.jaswal.gpxfileprocessor.common.entity.CompletionFlags;

import java.util.Optional;

public interface JobRepositoryCustom {
    CompletionFlags markCalculationsCompleteAtomically(Long id);
    CompletionFlags markEnrichmentCompleteAtomically(Long id);
    Optional<Integer> markApiCallReservedAtomically(int count);
}
