package com.jaswal.gpxfileprocessor.enrichment;

import com.jaswal.gpxfileprocessor.common.config.RabbitMQConfig;
import com.jaswal.gpxfileprocessor.common.entity.CompletionFlags;
import com.jaswal.gpxfileprocessor.common.entity.JobEntity;
import com.jaswal.gpxfileprocessor.common.exception.FileStorageException;
import com.jaswal.gpxfileprocessor.common.repository.JobRepository;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class Q3EnrichmentWorker {

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private RestClient.Builder restClientBuilder;

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

            CompletionFlags flags = jobRepository.markEnrichmentCompleteAtomically(jobId);
            if (flags.validationComplete() && flags.calculationsComplete() && flags.enrichmentComplete()) {
                System.out.println("Job " + jobId + ": Q3 won the completion race — all stages done");
            }

        } catch (Exception e) {
            throw new FileStorageException("Q3 failed processing job " + jobIdString + ": " + e.getMessage(), e);
        }
    }
}
