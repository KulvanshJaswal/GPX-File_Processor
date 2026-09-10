package com.jaswal.gpxfileprocessor.math;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaswal.gpxfileprocessor.common.config.RabbitMQConfig;
import com.jaswal.gpxfileprocessor.common.entity.CompletionFlags;
import com.jaswal.gpxfileprocessor.common.entity.Difficulty;
import com.jaswal.gpxfileprocessor.common.entity.JobEntity;
import com.jaswal.gpxfileprocessor.common.entity.RouteEntity;
import com.jaswal.gpxfileprocessor.common.exception.FileStorageException;
import com.jaswal.gpxfileprocessor.common.repository.JobRepository;
import com.jaswal.gpxfileprocessor.common.repository.RouteRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class Q2MathWorker {

    private static final double EARTH_RADIUS_METERS = 6371000.0;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public record DifficultyResult(Difficulty tier, double score) {}

    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private MinioClient minioClient;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Value("${minio.bucket-name}")
    private String bucketName;

    private double calculateHaversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);

        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(radLat1) * Math.cos(radLat2) * Math.pow(Math.sin(dLon / 2), 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_METERS * c;
    }

    private DifficultyResult calculateDifficulty(double elevationGainMeters, double distanceKm) {
        double elevationGainFeet = elevationGainMeters * 3.28084;
        double distanceMiles = distanceKm * 0.621371;

        double score = Math.sqrt(elevationGainFeet * 2 * distanceMiles);

        Difficulty tier;
        if (score < 50) {
            tier = Difficulty.EASIEST;
        } else if (score < 100) {
            tier = Difficulty.MODERATE;
        } else if (score < 150) {
            tier = Difficulty.MODERATELY_STRENUOUS;
        } else if (score < 200) {
            tier = Difficulty.STRENUOUS;
        } else {
            tier = Difficulty.VERY_STRENUOUS;
        }

        return new DifficultyResult(tier, score);
    }


    private double[] toXY(WayPoint point, double referenceLatRadians) {
        double lat = Math.toRadians(point.getLatitude().doubleValue());
        double lon = Math.toRadians(point.getLongitude().doubleValue());
        double x = EARTH_RADIUS_METERS * lon * Math.cos(referenceLatRadians);
        double y = EARTH_RADIUS_METERS * lat;
        return new double[]{x, y};
    }

    private double perpendicularDistance(WayPoint p, WayPoint lineStart, WayPoint lineEnd, double referenceLatRadians) {
        double[] pointCord = toXY(p, referenceLatRadians);
        double[] startCord = toXY(lineStart, referenceLatRadians);
        double[] endCord = toXY(lineEnd, referenceLatRadians);

        double x0 = pointCord[0];
        double y0 = pointCord[1];

        double x1 = startCord[0];
        double y1 = startCord[1];

        double x2 = endCord[0];
        double y2 = endCord[1];

        double numerator = Math.abs((y2 - y1) * x0 - (x2 - x1) * y0 + x2 * y1 - y2 * x1);
        double denominator = Math.sqrt(Math.pow(y2 - y1, 2) + Math.pow(x2 - x1, 2));

        if (denominator == 0) {
            // lineStart and lineEnd are the same point — fall back to straight-line distance
            return Math.sqrt(Math.pow(x0 - x1, 2) + Math.pow(y0 - y1, 2));
        }

        return numerator / denominator;
    }

    private List<WayPoint> simplify(List<WayPoint> points, double toleranceMeters, double referenceLatRadians) {
        if (points.size() < 3) {
            return points;
        }

        WayPoint lineStart = points.get(0);
        WayPoint lineEnd = points.get(points.size() - 1);

        double maxDistance = 0.0;
        int maxIndex = 0;

        for (int i = 1; i < points.size() - 1; i++) {
            double distance = perpendicularDistance(points.get(i), lineStart, lineEnd, referenceLatRadians);
            if (distance > maxDistance) {
                maxDistance = distance;
                maxIndex = i;
            }
        }

        if (maxDistance > toleranceMeters) {
            List<WayPoint> leftHalf = simplify(points.subList(0, maxIndex + 1), toleranceMeters, referenceLatRadians);
            List<WayPoint> rightHalf = simplify(points.subList(maxIndex, points.size()), toleranceMeters, referenceLatRadians);

            List<WayPoint> combined = new ArrayList<>(leftHalf.subList(0, leftHalf.size() - 1));
            combined.addAll(rightHalf);
            return combined;
        } else {
            return List.of(lineStart, lineEnd);
        }
    }

    @RabbitListener(queues = RabbitMQConfig.Q2_QUEUE)
    public void handleMath(String jobIdString) {
        Long jobId = Long.valueOf(jobIdString);
        JobEntity job = jobRepository.findById(jobId).orElseThrow(
                () -> new RuntimeException("Invalid jobId: " + jobIdString)
        );

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

            double totalDistanceMeters = 0.0;
            double elevationGain = 0.0;
            double elevationLoss = 0.0;
            Double minElevation = Double.POSITIVE_INFINITY;
            Double maxElevation = Double.NEGATIVE_INFINITY;
            double movingTime = 0.0;
            double totalTime = 0.0;

            for (int i = 0; i < allPoints.size(); i++) {
                WayPoint current = allPoints.get(i);

                double currentElevation = current.getElevation()
                        .map(Number::doubleValue)
                        .orElse(Double.NaN);

                if (!Double.isNaN(currentElevation)) {
                    minElevation = Math.min(minElevation, currentElevation);
                    maxElevation = Math.max(maxElevation, currentElevation);
                }

                if (i == 0) {
                    continue;
                }

                WayPoint previous = allPoints.get(i - 1);

                // Distance
                double distance = calculateHaversineDistance(
                        previous.getLatitude().doubleValue(),
                        previous.getLongitude().doubleValue(),
                        current.getLatitude().doubleValue(),
                        current.getLongitude().doubleValue()
                );
                totalDistanceMeters += distance;

                // Moving / total time
                Optional<Instant> previousTime = previous.getTime();
                Optional<Instant> currentTime = current.getTime();

                if (previousTime.isPresent() && currentTime.isPresent()) {
                    double elapsedSeconds = Duration.between(previousTime.get(), currentTime.get()).toMillis() / 1000.0;
                    double elapsedMinutes = elapsedSeconds / 60.0;

                    double speedMetersPerSecond = elapsedSeconds > 0 ? distance / elapsedSeconds : 0.0;

                    if (speedMetersPerSecond > 0.5) {
                        movingTime += elapsedMinutes;
                    }
                    totalTime += elapsedMinutes;
                }

                // Elevation gain / loss
                double previousElevation = previous.getElevation()
                        .map(Number::doubleValue)
                        .orElse(Double.NaN);

                if (!Double.isNaN(previousElevation) && !Double.isNaN(currentElevation)) {
                    double elevationChange = currentElevation - previousElevation;

                    if (elevationChange > 0) {
                        elevationGain += elevationChange;
                    } else {
                        elevationLoss += Math.abs(elevationChange);
                    }
                }
            }

            if (minElevation == Double.POSITIVE_INFINITY || maxElevation == Double.NEGATIVE_INFINITY) {
                minElevation = null;
                maxElevation = null;
            }

            double totalDistanceKm = totalDistanceMeters / 1000.0;
            double paceKmPerMinute = movingTime > 0 ? totalDistanceKm / movingTime : 0.0;

            //Difficulty Calc
            DifficultyResult difficulty = calculateDifficulty(elevationGain, totalDistanceKm);

            // RDP track simplification — 8 m tolerance, projected onto local flat plane
            double referenceLatRadians = Math.toRadians(allPoints.get(0).getLatitude().doubleValue());
            List<WayPoint> simplified = simplify(allPoints, 8.0, referenceLatRadians);
            List<double[]> points2d = simplified.stream()
                    .map(wp -> new double[]{wp.getLatitude().doubleValue(), wp.getLongitude().doubleValue()})
                    .toList();
            String simplifiedJson = OBJECT_MAPPER.writeValueAsString(points2d);
            RouteEntity route = new RouteEntity();
            route.setJob(job);
            route.setSimplifiedTrackpoints(simplifiedJson);
            routeRepository.save(route);

            jobRepository.updateCalculationResults(
                    jobId, totalDistanceKm, elevationGain, elevationLoss, maxElevation, minElevation,
                    (int) Math.round(movingTime * 60), (int) Math.round(totalTime * 60), paceKmPerMinute,
                    difficulty.tier().name(), difficulty.score());

            CompletionFlags flags = jobRepository.markCalculationsCompleteAtomically(jobId);
            if (flags.validationComplete() && flags.calculationsComplete() && flags.enrichmentComplete()) {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.GENERATOR_EXCHANGE,
                        RabbitMQConfig.GENERATOR_ROUTING_KEY,
                        jobIdString
                );
            }

        } catch (Exception e) {
            throw new FileStorageException("Error processing GPX distance calculation for job: " + jobIdString, e);
        }
    }
}