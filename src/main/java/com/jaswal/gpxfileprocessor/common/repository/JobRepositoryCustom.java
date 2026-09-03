package com.jaswal.gpxfileprocessor.common.repository;

import com.jaswal.gpxfileprocessor.common.entity.CompletionFlags;

public interface JobRepositoryCustom {
    CompletionFlags markCalculationsCompleteAtomically(Long id);
    CompletionFlags markEnrichmentCompleteAtomically(Long id);
}
