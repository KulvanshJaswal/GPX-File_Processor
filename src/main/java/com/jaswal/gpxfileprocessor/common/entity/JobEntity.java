package com.jaswal.gpxfileprocessor.common.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Table(name = "jobs")
@Entity
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class JobEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private JobStatus status;

    @Column(name = "validation_complete", nullable = false, columnDefinition = "boolean default false")
    private Boolean validationComplete;

    @Column(name = "calculations_complete", nullable = false, columnDefinition = "boolean default false")
    private Boolean calculationsComplete;

    @Column(name = "enrichment_complete", nullable = false, columnDefinition = "boolean default false")
    private Boolean enrichmentComplete;

    @Column(name = "distance_km")
    private Double distanceKm;

    @Column(name = "elevation_gain_m")
    private Double elevationGainM;

    @Column(name = "elevation_loss_m")
    private Double elevationLossM;

    @Column(name = "max_elevation_m")
    private Double maxElevationM;

    @Column(name = "min_elevation_m")
    private Double minElevationM;

    @Column(name = "moving_time_seconds")
    private Integer movingTimeSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty")
    private Difficulty difficulty;

    @Column(name = "difficulty_score")
    private Double difficultyScore;

    @Column(name = "bounding_box")
    private String boundingBox;

    @Type(JsonType.class)
    @Column(name = "weather_data", columnDefinition = "jsonb")
    private String weatherData;

    @Column(name = "pdf_path")
    private String pdfPath;

    @Column(name = "error_message")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}