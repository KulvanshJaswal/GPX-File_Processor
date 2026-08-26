package com.jaswal.gpxfileprocessor.common.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;

@Entity
@Table(name = "routes", indexes = {
        @Index(name = "idx_routes_job_id", columnList = "job_id"),
        @Index(name = "idx_routes_trail_fingerprint", columnList = "trail_fingerprint")
})
@Getter
@Setter
@NoArgsConstructor
public class RouteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @OneToOne
    @JoinColumn(name = "job_id", unique = true, nullable = false)
    private JobEntity job;

    @Type(JsonType.class)
    @Column(name = "simplified_trackpoints", columnDefinition = "jsonb")
    private String simplifiedTrackpoints;

    @Column(name = "trail_fingerprint")
    private String trailFingerprint;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
