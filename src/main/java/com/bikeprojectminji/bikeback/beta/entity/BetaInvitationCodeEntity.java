package com.bikeprojectminji.bikeback.beta.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;

@Entity
@Table(name = "beta_invitation_codes")
public class BetaInvitationCodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 40)
    private String code;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_by_user_id")
    private Long usedByUserId;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BetaInvitationCodeEntity() {
    }

    public BetaInvitationCodeEntity(String code, Instant expiresAt, Clock clock) {
        this.code = normalizeCode(code);
        this.expiresAt = expiresAt;
        this.createdAt = clock.instant();
    }

    public void markUsed(Long userId, Clock clock) {
        this.usedByUserId = userId;
        this.usedAt = clock.instant();
    }

    public boolean isUsed() {
        return usedByUserId != null;
    }

    public boolean isExpired(Clock clock) {
        return !expiresAt.isAfter(clock.instant());
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Long getUsedByUserId() {
        return usedByUserId;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private String normalizeCode(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().toUpperCase();
    }
}
