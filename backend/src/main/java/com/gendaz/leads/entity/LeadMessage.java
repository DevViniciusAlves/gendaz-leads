package com.gendaz.leads.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "lead_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lead_id", nullable = false, unique = true)
    private Long leadId;

    @Column(name = "message_text", nullable = false, columnDefinition = "TEXT")
    private String messageText;

    @Builder.Default
    private boolean edited = false;

    @Builder.Default
    private boolean approved = false;

    @Column(name = "generated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant generatedAt;

    @Column(name = "edited_at", columnDefinition = "TIMESTAMPTZ")
    private Instant editedAt;

    @PrePersist
    void onCreate() {
        this.generatedAt = Instant.now();
    }
}
