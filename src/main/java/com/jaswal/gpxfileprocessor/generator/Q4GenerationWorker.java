package com.jaswal.gpxfileprocessor.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.UnitValue;
import com.jaswal.gpxfileprocessor.common.config.RabbitMQConfig;
import com.jaswal.gpxfileprocessor.common.entity.JobEntity;
import com.jaswal.gpxfileprocessor.common.entity.JobStatus;
import com.jaswal.gpxfileprocessor.common.exception.FileStorageException;
import com.jaswal.gpxfileprocessor.common.repository.JobRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Component
public class Q4GenerationWorker {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public record DailyWeather(
            String date,
            int weatherCode,
            double tempMax,
            double tempMin,
            double precipitationSum,
            double snowfallSum,
            String sunrise,
            String sunset,
            double windSpeedMax
    ) {}

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucket-name}")
    private String bucketName;

    @RabbitListener(queues = RabbitMQConfig.Q4_QUEUE)
    public void pdfGeneration(String jobIdString) {
        Long jobId = Long.valueOf(jobIdString);
        JobEntity job = jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));

        try {
            ByteArrayOutputStream pdfBytes = new ByteArrayOutputStream();
            try (PdfWriter writer = new PdfWriter(pdfBytes);
                 PdfDocument pdfDoc = new PdfDocument(writer);
                 Document document = new Document(pdfDoc)) {

                addMetadataSection(document, job);
                addStatsTable(document, job);

                List<DailyWeather> weather = parseWeather(job.getWeatherData());
                if (weather != null && !weather.isEmpty()) {
                    addWeatherTable(document, weather);
                }
            }

            byte[] bytes = pdfBytes.toByteArray();
            String objectName = jobId + "_report.pdf";

            try (InputStream in = new ByteArrayInputStream(bytes)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .stream(in, bytes.length, -1)
                        .contentType("application/pdf")
                        .build());
            }

            job.setPdfPath(objectName);
            job.setWeatherData(null);
            job.setStatus(JobStatus.COMPLETE);
            jobRepository.save(job);

        } catch (Exception e) {
            throw new FileStorageException("Q4 failed processing job " + jobIdString + ": " + e.getMessage(), e);
        }
    }

    private List<DailyWeather> parseWeather(String weatherJson) {
        if (weatherJson == null || weatherJson.isBlank()) {
            return null;
        }
        try {
            JsonNode daily = OBJECT_MAPPER.readTree(weatherJson).path("daily");
            List<DailyWeather> result = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                result.add(new DailyWeather(
                        daily.path("time").path(i).asText(),
                        daily.path("weather_code").path(i).asInt(),
                        daily.path("temperature_2m_max").path(i).asDouble(),
                        daily.path("temperature_2m_min").path(i).asDouble(),
                        daily.path("precipitation_sum").path(i).asDouble(),
                        daily.path("snowfall_sum").path(i).asDouble(),
                        daily.path("sunrise").path(i).asText(),
                        daily.path("sunset").path(i).asText(),
                        daily.path("wind_speed_10m_max").path(i).asDouble()
                ));
            }
            return result;
        } catch (Exception e) {
            System.out.println("Job weather parse failed, skipping weather table: " + e.getMessage());
            return null;
        }
    }

    private void addMetadataSection(Document document, JobEntity job) {
        document.add(new Paragraph(job.getName()).simulateBold().setFontSize(20));
        document.add(new Paragraph("Date processed: " + job.getCreatedAt()));
        document.add(new Paragraph("Difficulty: " + job.getDifficulty() + " (score: " + job.getDifficultyScore() + ")"));
    }

    private void addStatsTable(Document document, JobEntity job) {
        Table table = new Table(UnitValue.createPercentArray(new float[]{1, 1})).useAllAvailableWidth();
        addStatRow(table, "Distance (km)", job.getDistanceKm());
        addStatRow(table, "Elevation gain (m)", job.getElevationGainM());
        addStatRow(table, "Elevation loss (m)", job.getElevationLossM());
        addStatRow(table, "Max elevation (m)", job.getMaxElevationM());
        addStatRow(table, "Min elevation (m)", job.getMinElevationM());
        table.addCell(new Cell().add(new Paragraph("Moving time")));
        table.addCell(new Cell().add(new Paragraph(formatDuration(job.getMovingTimeSeconds()))));
        addStatRow(table, "Pace (km/min)", job.getPaceKmPerMin());
        document.add(table);
    }

    private void addStatRow(Table table, String label, Double value) {
        table.addCell(new Cell().add(new Paragraph(label)));
        table.addCell(new Cell().add(new Paragraph(value == null ? "Not available" : String.valueOf(value))));
    }

    private String formatDuration(Integer totalSeconds) {
        if (totalSeconds == null) {
            return "Not available";
        }
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }

    private void addWeatherTable(Document document, List<DailyWeather> weather) {
        document.add(new Paragraph("7-Day Weather Forecast").simulateBold().setFontSize(14));

        Table table = new Table(UnitValue.createPercentArray(9)).useAllAvailableWidth();
        for (String header : new String[]{"Date", "Code", "High", "Low", "Precip", "Snow", "Sunrise", "Sunset", "Wind"}) {
            table.addHeaderCell(new Cell().add(new Paragraph(header)));
        }

        for (DailyWeather day : weather) {
            table.addCell(new Cell().add(new Paragraph(day.date())));
            table.addCell(new Cell().add(new Paragraph(String.valueOf(day.weatherCode()))));
            table.addCell(new Cell().add(new Paragraph(day.tempMax() + "°")));
            table.addCell(new Cell().add(new Paragraph(day.tempMin() + "°")));
            table.addCell(new Cell().add(new Paragraph(day.precipitationSum() + "mm")));
            table.addCell(new Cell().add(new Paragraph(day.snowfallSum() + "cm")));
            table.addCell(new Cell().add(new Paragraph(day.sunrise())));
            table.addCell(new Cell().add(new Paragraph(day.sunset())));
            table.addCell(new Cell().add(new Paragraph(day.windSpeedMax() + " km/h")));
        }

        document.add(table);
    }
}
