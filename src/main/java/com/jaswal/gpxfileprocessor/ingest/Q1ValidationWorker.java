package com.jaswal.gpxfileprocessor.ingest;

import com.jaswal.gpxfileprocessor.common.config.RabbitMQConfig;
import com.jaswal.gpxfileprocessor.common.entity.JobEntity;
import com.jaswal.gpxfileprocessor.common.exception.FileStorageException;
import com.jaswal.gpxfileprocessor.common.exception.InvalidGpxFileException;
import com.jaswal.gpxfileprocessor.common.repository.JobRepository;
import com.jaswal.gpxfileprocessor.common.util.RetryBackoff;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
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
import org.springframework.messaging.handler.annotation.Header;
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

    @RabbitListener(queues = Q1_QUEUE, concurrency = "1-2")
    public void handleIngest(
            String jobIdString,
            @Header(name = "x-retry-count", defaultValue = "0") Integer attemptCount
            ) {
        Long jobId = Long.valueOf(jobIdString);
        JobEntity job = jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));

        if (attemptCount >= 4) {
            rabbitTemplate.convertAndSend("", RabbitMQConfig.DLQ1_QUEUE, jobIdString);
            jobRepository.markJobFailed(jobId, "Job failed after " + attemptCount + " attempts in Q1 validation");
        } else {

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

                WayPoint start = allPoints.get(0);
                job.setStartLat(start.getLatitude().doubleValue());
                job.setStartLon(start.getLongitude().doubleValue());

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
                MessageProperties prop = new MessageProperties();
                prop.setHeader("x-retry-count", attemptCount + 1);
                prop.setHeader("x-delay", RetryBackoff.getDelayMillis(attemptCount));

                Message message = new Message(jobIdString.getBytes(), prop);
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.DELAYED_RETRY_EXCHANGE,
                        RabbitMQConfig.Q1_RETRY_ROUTING_KEY,
                        message
                );
            }
        }
    }
}