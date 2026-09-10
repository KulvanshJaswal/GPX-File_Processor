package com.jaswal.gpxfileprocessor.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.jaswal.gpxfileprocessor.common.config.RabbitMQConfig;
import com.jaswal.gpxfileprocessor.common.entity.JobEntity;
import com.jaswal.gpxfileprocessor.common.entity.JobStatus;
import com.jaswal.gpxfileprocessor.common.exception.FileStorageException;
import com.jaswal.gpxfileprocessor.common.repository.JobRepository;
import io.jenetics.jpx.GPX;
import io.jenetics.jpx.Track;
import io.jenetics.jpx.TrackSegment;
import io.jenetics.jpx.WayPoint;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

@Component
public class Q4GenerationWorker {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final double EARTH_RADIUS_METERS = 6371000.0;
    private static final int TILE_SIZE = 256;
    private static final int MAX_TILES = 9; // 3x3 grid budget — keeps OSM usage light
    private static final String OSM_USER_AGENT =
            "GPX-File-Processor/1.0 (portfolio project; github.com/KulvanshJaswal/gpx-file-processor)";

    // Hiking/outdoors palette — PDF layout (iText) colors
    private static final DeviceRgb PDF_DARK_GREEN = new DeviceRgb(45, 74, 42);
    private static final DeviceRgb PDF_ACCENT_BROWN = new DeviceRgb(139, 90, 43);
    private static final DeviceRgb PDF_CREAM = new DeviceRgb(250, 246, 238);
    private static final DeviceRgb PDF_WHITE = new DeviceRgb(255, 255, 255);
    private static final DeviceRgb PDF_TEXT_DARK = new DeviceRgb(58, 47, 40);
    private static final DeviceRgb PDF_BORDER = new DeviceRgb(214, 200, 176);

    // Same palette, AWT Color — used for JFreeChart, which doesn't take iText's Color type
    private static final Color CHART_GREEN = new Color(90, 130, 74);
    private static final Color CHART_BACKGROUND = new Color(250, 246, 238);
    private static final Color CHART_GRID = new Color(224, 214, 194);
    private static final Color CHART_TITLE = new Color(45, 74, 42);

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

    @Autowired
    private RestClient.Builder restClientBuilder;

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

                try {
                    List<WayPoint> points = fetchTrackpoints(job);
                    addElevationChart(document, points);
                    addMapThumbnail(document, points);
                } catch (Exception e) {
                    System.out.println("Trackpoint visuals failed for job " + jobId + ", skipping: " + e.getMessage());
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

    private List<WayPoint> fetchTrackpoints(JobEntity job) throws Exception {
        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(job.getName())
                        .build())) {

            GPX gpx = GPX.Reader.DEFAULT.read(inputStream);
            return gpx.tracks()
                    .flatMap(Track::segments)
                    .flatMap(TrackSegment::points)
                    .toList();
        }
    }

    private boolean hasElevationData(List<WayPoint> points) {
        return points.stream().anyMatch(wp -> wp.getElevation().isPresent());
    }

    private double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);

        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(radLat1) * Math.cos(radLat2) * Math.pow(Math.sin(dLon / 2), 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_METERS * c;
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
        Table banner = new Table(UnitValue.createPercentArray(new float[]{1})).useAllAvailableWidth();
        banner.addCell(new Cell()
                .add(new Paragraph(job.getName())
                        .setFontColor(PDF_WHITE)
                        .setFontSize(22)
                        .simulateBold()
                        .setTextAlignment(TextAlignment.CENTER))
                .setBackgroundColor(PDF_DARK_GREEN)
                .setPadding(16)
                .setBorder(Border.NO_BORDER));
        document.add(banner);

        document.add(new Paragraph("Date processed: " + job.getCreatedAt())
                .setFontColor(PDF_TEXT_DARK)
                .setMarginTop(10));
        document.add(new Paragraph("Difficulty: " + job.getDifficulty() + " (score: " + job.getDifficultyScore() + ")")
                .setFontColor(PDF_ACCENT_BROWN)
                .simulateBold());
    }

    private void addSectionHeader(Document document, String title) {
        Table header = new Table(UnitValue.createPercentArray(new float[]{1})).useAllAvailableWidth();
        header.setMarginTop(14);
        header.addCell(new Cell()
                .add(new Paragraph(title).setFontColor(PDF_WHITE).setFontSize(13).simulateBold())
                .setBackgroundColor(PDF_ACCENT_BROWN)
                .setPadding(7)
                .setBorder(Border.NO_BORDER));
        document.add(header);
    }

    private void addStatsTable(Document document, JobEntity job) {
        Table table = new Table(UnitValue.createPercentArray(new float[]{1, 1})).useAllAvailableWidth();
        table.setMarginTop(12);
        int row = 0;
        addStatRow(table, "Distance (km)", formatStat(job.getDistanceKm()), row++);
        addStatRow(table, "Elevation gain (m)", formatStat(job.getElevationGainM()), row++);
        addStatRow(table, "Elevation loss (m)", formatStat(job.getElevationLossM()), row++);
        addStatRow(table, "Max elevation (m)", formatStat(job.getMaxElevationM()), row++);
        addStatRow(table, "Min elevation (m)", formatStat(job.getMinElevationM()), row++);
        addStatRow(table, "Moving time", formatDuration(job.getMovingTimeSeconds()), row++);
        addStatRow(table, "Pace (km/min)", formatStat(job.getPaceKmPerMin()), row++);
        document.add(table);
    }

    private String formatStat(Double value) {
        return value == null ? "Not available" : String.valueOf(value);
    }

    private void addStatRow(Table table, String label, String value, int rowIndex) {
        DeviceRgb rowColor = rowIndex % 2 == 0 ? PDF_CREAM : PDF_WHITE;
        table.addCell(new Cell()
                .add(new Paragraph(label).setFontColor(PDF_TEXT_DARK).simulateBold())
                .setBackgroundColor(rowColor)
                .setPadding(6)
                .setBorder(new SolidBorder(PDF_BORDER, 0.5f)));
        table.addCell(new Cell()
                .add(new Paragraph(value).setFontColor(PDF_TEXT_DARK))
                .setBackgroundColor(rowColor)
                .setPadding(6)
                .setBorder(new SolidBorder(PDF_BORDER, 0.5f)));
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
        addSectionHeader(document, "7-Day Weather Forecast");

        Table table = new Table(UnitValue.createPercentArray(9)).useAllAvailableWidth();
        table.setMarginTop(8);
        for (String header : new String[]{"Date", "Code", "High", "Low", "Precip", "Snow", "Sunrise", "Sunset", "Wind"}) {
            table.addHeaderCell(new Cell()
                    .add(new Paragraph(header).setFontColor(PDF_WHITE).setFontSize(9).simulateBold())
                    .setBackgroundColor(PDF_DARK_GREEN)
                    .setPadding(5)
                    .setBorder(new SolidBorder(PDF_BORDER, 0.5f)));
        }

        int row = 0;
        for (DailyWeather day : weather) {
            DeviceRgb rowColor = row % 2 == 0 ? PDF_CREAM : PDF_WHITE;
            addWeatherCell(table, day.date(), rowColor);
            addWeatherCell(table, String.valueOf(day.weatherCode()), rowColor);
            addWeatherCell(table, day.tempMax() + "°", rowColor);
            addWeatherCell(table, day.tempMin() + "°", rowColor);
            addWeatherCell(table, day.precipitationSum() + "mm", rowColor);
            addWeatherCell(table, day.snowfallSum() + "cm", rowColor);
            addWeatherCell(table, day.sunrise(), rowColor);
            addWeatherCell(table, day.sunset(), rowColor);
            addWeatherCell(table, day.windSpeedMax() + " km/h", rowColor);
            row++;
        }

        document.add(table);
    }

    private void addWeatherCell(Table table, String text, DeviceRgb backgroundColor) {
        table.addCell(new Cell()
                .add(new Paragraph(text).setFontColor(PDF_TEXT_DARK).setFontSize(9))
                .setBackgroundColor(backgroundColor)
                .setPadding(4)
                .setBorder(new SolidBorder(PDF_BORDER, 0.5f)));
    }

    private void addElevationChart(Document document, List<WayPoint> points) throws Exception {
        if (!hasElevationData(points)) {
            System.out.println("No elevation data available, skipping elevation chart");
            return;
        }

        XYSeries series = new XYSeries("Elevation");
        double cumulativeKm = 0.0;
        WayPoint previous = null;
        for (WayPoint point : points) {
            if (previous != null) {
                cumulativeKm += haversineMeters(
                        previous.getLatitude().doubleValue(), previous.getLongitude().doubleValue(),
                        point.getLatitude().doubleValue(), point.getLongitude().doubleValue()) / 1000.0;
            }
            double distanceSoFar = cumulativeKm;
            point.getElevation().ifPresent(e -> series.add(distanceSoFar, e.doubleValue()));
            previous = point;
        }

        JFreeChart chart = ChartFactory.createXYAreaChart(
                "Elevation Profile", "Distance (km)", "Elevation (m)", new XYSeriesCollection(series));

        chart.setBackgroundPaint(CHART_BACKGROUND);
        chart.getTitle().setPaint(CHART_TITLE);

        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(CHART_BACKGROUND);
        plot.setDomainGridlinePaint(CHART_GRID);
        plot.setRangeGridlinePaint(CHART_GRID);
        plot.setOutlineVisible(false);
        plot.getRenderer().setSeriesPaint(0, CHART_GREEN);

        BufferedImage chartImage = chart.createBufferedImage(500, 300);

        ByteArrayOutputStream imgBytes = new ByteArrayOutputStream();
        ImageIO.write(chartImage, "png", imgBytes);
        document.add(new Image(ImageDataFactory.create(imgBytes.toByteArray())));
    }

    private int lonToTileX(double lon, int zoom) {
        return (int) Math.floor((lon + 180.0) / 360.0 * (1 << zoom));
    }

    private int latToTileY(double lat, int zoom) {
        double latRad = Math.toRadians(lat);
        return (int) Math.floor((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * (1 << zoom));
    }

    private double lonToPixelX(double lon, int zoom) {
        return (lon + 180.0) / 360.0 * (1 << zoom) * TILE_SIZE;
    }

    private double latToPixelY(double lat, int zoom) {
        double latRad = Math.toRadians(lat);
        return (1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * (1 << zoom) * TILE_SIZE;
    }

    private int chooseZoom(double minLat, double minLon, double maxLat, double maxLon) {
        for (int zoom = 18; zoom >= 1; zoom--) {
            int minTileX = lonToTileX(minLon, zoom);
            int maxTileX = lonToTileX(maxLon, zoom);
            int minTileY = latToTileY(maxLat, zoom);
            int maxTileY = latToTileY(minLat, zoom);
            long tileCount = (long) (maxTileX - minTileX + 1) * (maxTileY - minTileY + 1);
            if (tileCount <= MAX_TILES) {
                return zoom;
            }
        }
        return 1;
    }

    private void addMapThumbnail(Document document, List<WayPoint> points) {
        try {
            double minLat = points.stream().mapToDouble(p -> p.getLatitude().doubleValue()).min().orElseThrow();
            double maxLat = points.stream().mapToDouble(p -> p.getLatitude().doubleValue()).max().orElseThrow();
            double minLon = points.stream().mapToDouble(p -> p.getLongitude().doubleValue()).min().orElseThrow();
            double maxLon = points.stream().mapToDouble(p -> p.getLongitude().doubleValue()).max().orElseThrow();

            int zoom = chooseZoom(minLat, minLon, maxLat, maxLon);
            int minTileX = lonToTileX(minLon, zoom);
            int maxTileX = lonToTileX(maxLon, zoom);
            int minTileY = latToTileY(maxLat, zoom);
            int maxTileY = latToTileY(minLat, zoom);

            BufferedImage composite = new BufferedImage(
                    (maxTileX - minTileX + 1) * TILE_SIZE, (maxTileY - minTileY + 1) * TILE_SIZE,
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = composite.createGraphics();

            for (int x = minTileX; x <= maxTileX; x++) {
                for (int y = minTileY; y <= maxTileY; y++) {
                    byte[] tile = restClientBuilder.build()
                            .get()
                            .uri("https://tile.openstreetmap.org/{z}/{x}/{y}.png", zoom, x, y)
                            .header(HttpHeaders.USER_AGENT, OSM_USER_AGENT)
                            .retrieve()
                            .body(byte[].class);
                    g.drawImage(ImageIO.read(new ByteArrayInputStream(tile)),
                            (x - minTileX) * TILE_SIZE, (y - minTileY) * TILE_SIZE, null);
                }
            }

            boolean colorByGrade = hasElevationData(points);
            g.setStroke(new BasicStroke(3));
            WayPoint previous = null;
            for (WayPoint point : points) {
                if (previous != null) {
                    int x1 = (int) (lonToPixelX(previous.getLongitude().doubleValue(), zoom) - minTileX * TILE_SIZE);
                    int y1 = (int) (latToPixelY(previous.getLatitude().doubleValue(), zoom) - minTileY * TILE_SIZE);
                    int x2 = (int) (lonToPixelX(point.getLongitude().doubleValue(), zoom) - minTileX * TILE_SIZE);
                    int y2 = (int) (latToPixelY(point.getLatitude().doubleValue(), zoom) - minTileY * TILE_SIZE);
                    g.setColor(colorByGrade ? gradeColor(previous, point) : Color.BLUE);
                    g.drawLine(x1, y1, x2, y2);
                }
                previous = point;
            }

            g.setColor(new Color(0, 0, 0, 160));
            g.fillRect(0, composite.getHeight() - 16, 170, 16);
            g.setColor(Color.WHITE);
            g.drawString("© OpenStreetMap contributors", 4, composite.getHeight() - 4);
            g.dispose();

            ByteArrayOutputStream imgBytes = new ByteArrayOutputStream();
            ImageIO.write(composite, "png", imgBytes);
            document.add(new Image(ImageDataFactory.create(imgBytes.toByteArray())));

        } catch (Exception e) {
            System.out.println("Map thumbnail generation failed, skipping: " + e.getMessage());
        }
    }

    private Color gradeColor(WayPoint a, WayPoint b) {
        double horizontalM = haversineMeters(
                a.getLatitude().doubleValue(), a.getLongitude().doubleValue(),
                b.getLatitude().doubleValue(), b.getLongitude().doubleValue());
        double elevA = a.getElevation().map(Number::doubleValue).orElse(0.0);
        double elevB = b.getElevation().map(Number::doubleValue).orElse(0.0);
        double gradePercent = horizontalM > 0 ? Math.abs(elevB - elevA) / horizontalM * 100 : 0;

        double t = Math.min(gradePercent / 20.0, 1.0);
        if (t < 0.5) {
            return interpolate(new Color(30, 160, 30), new Color(230, 200, 0), t / 0.5);
        }
        return interpolate(new Color(230, 200, 0), new Color(220, 30, 30), (t - 0.5) / 0.5);
    }

    private Color interpolate(Color from, Color to, double t) {
        return new Color(
                (int) (from.getRed() + (to.getRed() - from.getRed()) * t),
                (int) (from.getGreen() + (to.getGreen() - from.getGreen()) * t),
                (int) (from.getBlue() + (to.getBlue() - from.getBlue()) * t));
    }
}
