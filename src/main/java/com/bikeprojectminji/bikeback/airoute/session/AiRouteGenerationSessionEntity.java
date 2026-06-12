package com.bikeprojectminji.bikeback.airoute.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ai_route_generation_sessions")
public class AiRouteGenerationSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AiRouteGenerationSessionStatus status;

    @Column(name = "fallback_used", nullable = false)
    private boolean fallbackUsed;

    @Column(nullable = false, length = 60)
    private String provider;

    @Column(name = "fallback_reason", length = 120)
    private String fallbackReason;

    @Column(name = "request_text", length = 1000)
    private String requestText;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AiRouteGenerationSessionEntity() {
    }

    public AiRouteGenerationSessionEntity(
            Long ownerUserId,
            AiRouteGenerationSessionStatus status,
            boolean fallbackUsed,
            String provider,
            String fallbackReason,
            String requestText
    ) {
        this.ownerUserId = ownerUserId;
        this.status = status;
        this.fallbackUsed = fallbackUsed;
        this.provider = provider;
        this.fallbackReason = fallbackReason;
        this.requestText = requestText;
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public AiRouteGenerationSessionStatus getStatus() {
        return status;
    }

    public boolean isFallbackUsed() {
        return fallbackUsed;
    }

    public String getProvider() {
        return provider;
    }

    public String getFallbackReason() {
        return fallbackReason;
    }

    public String getRequestText() {
        return requestText;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
