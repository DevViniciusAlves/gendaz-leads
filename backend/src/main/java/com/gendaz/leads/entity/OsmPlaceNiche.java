package com.gendaz.leads.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "osm_place_niches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OsmPlaceNiche {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "place_id", nullable = false)
    private OsmPlace place;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_id", nullable = false)
    private OsmCatalogTarget target;

    @Column(name = "canonical_niche", nullable = false, length = 255)
    private String canonicalNiche;

    @Column(name = "niche_evidence_type", nullable = false, length = 50)
    @Builder.Default
    private String nicheEvidenceType = "TAG_STRONG";

    @Column(name = "niche_evidence_details", columnDefinition = "TEXT")
    private String nicheEvidenceDetails;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "qualified_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant qualifiedAt;

    @Column(name = "last_seen_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant lastSeenAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (this.qualifiedAt == null) this.qualifiedAt = now;
        if (this.lastSeenAt == null) this.lastSeenAt = now;
    }
}
