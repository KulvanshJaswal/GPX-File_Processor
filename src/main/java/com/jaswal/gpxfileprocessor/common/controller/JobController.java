package com.jaswal.gpxfileprocessor.common.controller;

import com.jaswal.gpxfileprocessor.common.entity.JobEntity;
import com.jaswal.gpxfileprocessor.common.repository.JobRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.Map;

@RestController
public class JobController {

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucket-name}")
    private String bucketName;

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

    @GetMapping("/jobs/{id}/pdf")
    public ResponseEntity<?> getPdf(@PathVariable Long id) {
        JobEntity job = jobRepository.findById(id).orElse(null);
        if (job == null || job.getPdfPath() == null) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucketName).object(job.getPdfPath()).build())) {
            byte[] bytes = in.readAllBytes();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + job.getPdfPath() + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
