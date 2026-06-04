package com.bikeprojectminji.bikeback.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.OffsetDateTime;

@Entity
@Table(name = "kakao_account_links")
public class KakaoAccountLinkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "provider_user_id", nullable = false, unique = true, length = 80)
    private String providerUserId;

    @Column(name = "linked_at", nullable = false)
    private OffsetDateTime linkedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected KakaoAccountLinkEntity() {
    }

    public KakaoAccountLinkEntity(Long userId, String providerUserId, Clock clock) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        this.userId = userId;
        this.providerUserId = providerUserId;
        this.linkedAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public OffsetDateTime getLinkedAt() {
        return linkedAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
