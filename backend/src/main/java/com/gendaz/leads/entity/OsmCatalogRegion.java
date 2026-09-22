package com.gendaz.leads.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "osm_catalog_regions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OsmCatalogRegion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "city", nullable = false)
    private String city;

    @Column(name = "normalized_city", nullable = false)
    private String normalizedCity;

    @Column(name = "state")
    private String state;

    @Column(name = "normalized_state")
    private String normalizedState;

    @Column(name = "country", nullable = false)
    private String country;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(name = "osm_type", length = 10)
    private String osmType;

    @Column(name = "osm_id")
    private Long osmId;

    @Column(name = "geofabrik_region", length = 100)
    private String geofabrikRegion;

    @Column(name = "catalog_status", nullable = false, length = 20)
    @Builder.Default
    private String catalogStatus = "EMPTY";

    @Column(name = "last_success_at", columnDefinition = "TIMESTAMPTZ")
    private Instant lastSuccessAt;

    @Column(name = "last_attempt_at", columnDefinition = "TIMESTAMPTZ")
    private Instant lastAttemptAt;

    @Column(name = "place_count", nullable = false)
    @Builder.Default
    private Integer placeCount = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}