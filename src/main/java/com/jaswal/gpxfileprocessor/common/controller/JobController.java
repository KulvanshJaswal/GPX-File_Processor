package com.jaswal.gpxfileprocessor.common.controller;

import com.jaswal.gpxfileprocessor.common.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class JobController {

    @Autowired
    private JobRepository jobRepository;

    @GetMapping("/jobs/{id}")
    public ResponseEntity<?> getJob(@PathVariable Long id) {
        return jobRepository.findById(id)
                .map(job -> ResponseEntity.ok((Object) Map.of(
                        "jobId", job.getId(),
                        "status", job.getStatus(),
                        "distanceKm", job.getDistanceKm() != null ? job.getDistanceKm() : 0,
                        "difficulty", job.getDifficulty() != null ? job.getDifficulty() : "PENDING",
                        "pdfPath", job.getPdfPath() != null ? job.getPdfPath() : "",
                        "errorMessage", job.getErrorMessage() != null ? job.getErrorMessage() : ""
                )))
                .orElse(ResponseEntity.notFound().build());
    }
}