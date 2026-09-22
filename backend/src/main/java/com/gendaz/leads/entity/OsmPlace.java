package com.gendaz.leads.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "osm_places")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OsmPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", nullable = false)
    private OsmCatalogRegion region;

    @Column(name = "osm_type", nullable = false, length = 10)
    private String osmType;

    @Column(name = "osm_id", nullable = false)
    private Long osmId;

    @Column(name = "business_name", length = 500)
    private String businessName;

    @Column(name = "normalized_name", length = 500)
    private String normalizedName;

    @Column(name = "latitude", nullable = false)
    private Double latitude;

    @Column(name = "longitude", nullable = false)
    private Double longitude;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "city", length = 255)
    private String city;

    @Column(name = "state", length = 255)
    private String state;

    @Column(name = "country", length = 255)
    private String country;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(name = "phone", length = 100)
    private String phone;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "website", length = 500)
    private String website;

    @Column(name = "instagram", length = 255)
    private String instagram;

    @Column(name = "tags", columnDefinition = "JSONB")
    private String tags;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "first_seen_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant firstSeenAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant lastSeenAt = Instant.now();

    @Column(name = "source_timestamp", columnDefinition = "TIMESTAMPTZ")
    private Instant sourceTimestamp;

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