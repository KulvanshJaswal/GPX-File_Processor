package com.jaswal.gpxfileprocessor.common.repository;

import com.jaswal.gpxfileprocessor.common.entity.CompletionFlags;

public interface JobRepositoryCustom {
    CompletionFlags markValidationCompleteAtomically(Long id);
    CompletionFlags markCalculationsCompleteAtomically(Long id);
    CompletionFlags markEnrichmentCompleteAtomically(Long id);
}
