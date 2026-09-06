package com.jaswal.gpxfileprocessor.enrichment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jaswal.gpxfileprocessor.common.config.RabbitMQConfig;
import com.jaswal.gpxfileprocessor.common.entity.CompletionFlags;
import com.jaswal.gpxfileprocessor.common.entity.JobEntity;
import com.jaswal.gpxfileprocessor.common.exception.ApiRateLimitExceededException;
import com.jaswal.gpxfileprocessor.common.exception.FileStorageException;
import com.jaswal.gpxfileprocessor.common.repository.JobRepository;
import io.jenetics.jpx.GPX;
import io.jenetics.jpx.Track;
import io.jenetics.jpx.TrackSegment;
import io.jenetics.jpx.WayPoint;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class Q3EnrichmentWorker {

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucket-name}")
    private String bucketName;

    @RabbitListener(queues = RabbitMQConfig.Q3_QUEUE)
    public void handleEnrichment(String jobIdString) {
        Long jobId = Long.valueOf(jobIdString);
        JobEntity job = jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));

        try {
            String weatherJson = restClientBuilder.build()
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("api.open-meteo.com")
                            .path("/v1/forecast")
                            .queryParam("latitude", job.getStartLat())
                            .queryParam("longitude", job.getStartLon())
                            .queryParam("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,snowfall_sum,sunrise,sunset,wind_speed_10m_max")
                            .queryParam("timezone", "auto")
                            .build())
                    .retrieve()
                    .body(String.class);

            job.setWeatherData(weatherJson);
            jobRepository.save(job);

            if (job.getMaxElevationM() == null) {
                try {
                    correctElevation(job, jobId);
                } catch (ApiRateLimitExceededException e) {
                    System.out.println(e.getMessage());
                }
            }

            CompletionFlags flags = jobRepository.markEnrichmentCompleteAtomically(jobId);
            if (flags.validationComplete() && flags.calculationsComplete() && flags.enrichmentComplete()) {
                System.out.println("Job " + jobId + ": Q3 won the completion race — all stages done");
            }

        } catch (Exception e) {
            throw new FileStorageException("Q3 failed processing job " + jobIdString + ": " + e.getMessage(), e);
        }
    }

    private void correctElevation(JobEntity job, Long jobId) throws Exception {
        List<WayPoint> allPoints;
        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(job.getName())
                        .build())) {

            GPX gpx = GPX.Reader.DEFAULT.read(inputStream);
            allPoints = gpx.tracks()
                    .flatMap(Track::segments)
                    .flatMap(TrackSegment::points)
                    .toList();
        }

        int batchCount = (int) Math.ceil(allPoints.size() / 100.0);
        Optional<Integer> reserved = jobRepository.markApiCallReservedAtomically(batchCount);
        if (reserved.isEmpty()) {
            throw new ApiRateLimitExceededException(
                    "Job " + jobId + ": OpenTopoData daily rate limit reached, skipping elevation correction");
        }

        Double maxElevation = null;
        Double minElevation = null;
        Double previousElevation = null;
        double elevationGain = 0.0;
        double elevationLoss = 0.0;

        for (int i = 0; i < allPoints.size(); i += 100) {
            List<WayPoint> chunk = allPoints.subList(i, Math.min(i + 100, allPoints.size()));

            String locations = chunk.stream()
                    .map(wp -> wp.getLatitude().doubleValue() + "," + wp.getLongitude().doubleValue())
                    .collect(Collectors.joining("|"));

            ElevationResponse response = restClientBuilder.build()
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("api.opentopodata.org")
                            .path("/v1/mapzen")
                            .queryParam("locations", locations)
                            .build())
                    .retrieve()
                    .body(ElevationResponse.class);

            for (ElevationResult result : response.results()) {
                double elevation = result.elevation();

                if (maxElevation == null) {
                    maxElevation = elevation;
                    minElevation = elevation;
                } else {
                    maxElevation = Math.max(maxElevation, elevation);
                    minElevation = Math.min(minElevation, elevation);
                }

                if (previousElevation != null) {
                    double change = elevation - previousElevation;
                    if (change > 0) {
                        elevationGain += change;
                    } else {
                        elevationLoss += Math.abs(change);
                    }
                }
                previousElevation = elevation;
            }

            if (i + 100 < allPoints.size()) {
                try {
                    Thread.sleep(1100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
        }

        job.setMaxElevationM(maxElevation);
        job.setMinElevationM(minElevation);
        job.setElevationGainM(elevationGain);
        job.setElevationLossM(elevationLoss);
        jobRepository.save(job);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ElevationResponse(List<ElevationResult> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ElevationResult(double elevation) {}
}
