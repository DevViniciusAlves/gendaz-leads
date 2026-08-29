package com.gendaz.leads.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "lead_sources")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    @Column(nullable = false)
    private String source;

    @Column(name = "source_id")
    private String sourceId;

    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "found_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant foundAt;

    @PrePersist
    void onCreate() {
        if (this.foundAt == null) this.foundAt = Instant.now();
    }
}
