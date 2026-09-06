package com.jaswal.gpxfileprocessor.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "api_rate_limit")
@Getter
@Setter
@NoArgsConstructor
public class ApiRateLimitEntity {
    @Id
    private Long id;

    @Column(name = "call_count", nullable = false)
    private int callCount;

    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;
}
