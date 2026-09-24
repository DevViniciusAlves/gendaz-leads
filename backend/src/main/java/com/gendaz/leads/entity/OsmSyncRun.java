package com.gendaz.leads.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "osm_sync_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OsmSyncRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", nullable = false)
    private OsmCatalogRegion region;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_user_id", nullable = false)
    private User requestedByUser;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "QUEUED";

    @Column(name = "started_at", columnDefinition = "TIMESTAMPTZ")
    private Instant startedAt;

    @Column(name = "finished_at", columnDefinition = "TIMESTAMPTZ")
    private Instant finishedAt;

    @Column(name = "places_read", nullable = false)
    @Builder.Default
    private Long placesRead = 0L;

    @Column(name = "places_staged", nullable = false)
    @Builder.Default
    private Long placesStaged = 0L;

    @Column(name = "places_inserted", nullable = false)
    @Builder.Default
    private Long placesInserted = 0L;

    @Column(name = "places_updated", nullable = false)
    @Builder.Default
    private Long placesUpdated = 0L;

    @Column(name = "places_deactivated", nullable = false)
    @Builder.Default
    private Long placesDeactivated = 0L;

    @Column(name = "github_run_id")
    private Long githubRunId;

@Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // New fields for niche-aware sync
    @Column(name = "requested_niche", length = 255)
    private String requestedNiche;

    @Column(name = "canonical_niche", length = 255)
    private String canonicalNiche;

    @Column(name = "niche_strategy_json", columnDefinition = "TEXT")
    private String nicheStrategyJson;

    @Column(name = "target_valid")
    @Builder.Default
    private Integer targetValid = 50;

    @Column(name = "candidates_scanned")
    @Builder.Default
    private Long candidatesScanned = 0L;

    @Column(name = "niche_matches")
    @Builder.Default
    private Long nicheMatches = 0L;

    @Column(name = "discarded_no_phone")
    @Builder.Default
    private Long discardedNoPhone = 0L;

    @Column(name = "discarded_no_instagram")
    @Builder.Default
    private Long discardedNoInstagram = 0L;

    @Column(name = "discarded_not_on_whatsapp")
    @Builder.Default
    private Long discardedNotOnWhatsApp = 0L;

    @Column(name = "discarded_duplicate")
    @Builder.Default
    private Long discardedDuplicate = 0L;

    @Column(name = "technical_failures")
    @Builder.Default
    private Long technicalFailures = 0L;

    @Column(name = "qualified_saved")
    @Builder.Default
    private Long qualifiedSaved = 0L;

    @Column(name = "dataset_exhausted")
    @Builder.Default
    private Boolean datasetExhausted = false;

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