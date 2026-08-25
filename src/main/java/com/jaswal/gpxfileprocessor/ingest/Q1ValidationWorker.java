package com.jaswal.gpxfileprocessor.ingest;

import com.jaswal.gpxfileprocessor.common.config.RabbitMQConfig;
import com.jaswal.gpxfileprocessor.common.entity.JobEntity;
import com.jaswal.gpxfileprocessor.common.exception.FileStorageException;
import com.jaswal.gpxfileprocessor.common.exception.InvalidGpxFileException;
import com.jaswal.gpxfileprocessor.common.repository.JobRepository;
import io.jenetics.jpx.GPX;
import io.jenetics.jpx.Track;
import io.jenetics.jpx.TrackSegment;
import io.jenetics.jpx.WayPoint;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

import static com.jaswal.gpxfileprocessor.common.config.RabbitMQConfig.Q1_QUEUE;

@Component
public class Q1ValidationWorker {

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Value("${minio.bucket-name}")
    private String bucketName;

    @RabbitListener(queues = Q1_QUEUE)
    public void handleIngest(String jobIdString) {
        Long jobId = Long.valueOf(jobIdString);
        JobEntity job = jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));

        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(job.getName())
                        .build())) {

            GPX gpx = GPX.Reader.DEFAULT.read(inputStream);

            List<WayPoint> allPoints = gpx.tracks()
                    .flatMap(Track::segments)
                    .flatMap(TrackSegment::points)
                    .toList();

            if (allPoints.isEmpty()) {
                throw new InvalidGpxFileException("GPX file contains no trackpoints");
            }

            job.setValidationComplete(true);
            jobRepository.save(job);

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.MAIN_EXCHANGE,
                    RabbitMQConfig.SERVICE_ROUTING_KEY,
                    jobIdString
            );

        } catch (InvalidGpxFileException e) {
            throw e;
        } catch (Exception e) {
            throw new FileStorageException("Q1 failed processing job " + jobIdString + ": " + e.getMessage(), e);
        }
    }
}