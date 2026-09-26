package com.gendaz.leads.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "osm_catalog_targets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OsmCatalogTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", nullable = false)
    private OsmCatalogRegion region;

    @Column(name = "requested_niche", nullable = false, length = 255)
    private String requestedNiche;

    @Column(name = "canonical_niche", nullable = false, length = 255)
    private String canonicalNiche;

    @Column(name = "target_valid", nullable = false)
    @Builder.Default
    private Integer targetValid = 50;

    @Column(name = "qualified_count", nullable = false)
    @Builder.Default
    private Integer qualifiedCount = 0;

    @Column(name = "available_new_count", nullable = false)
    @Builder.Default
    private Integer availableNewCount = 0;

    @Column(name = "pool_status", nullable = false, length = 20)
    @Builder.Default
    private String poolStatus = "EMPTY";

    @Column(name = "last_success_at", columnDefinition = "TIMESTAMPTZ")
    private Instant lastSuccessAt;

    @Column(name = "last_attempt_at", columnDefinition = "TIMESTAMPTZ")
    private Instant lastAttemptAt;

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
