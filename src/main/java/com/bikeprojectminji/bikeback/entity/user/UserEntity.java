package com.bikeprojectminji.bikeback.entity.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true, length = 120)
    private String externalId;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected UserEntity() {
    }

    public UserEntity(String externalId, String displayName, String profileImageUrl) {
        this.externalId = externalId;
        this.displayName = displayName;
        this.profileImageUrl = profileImageUrl;
    }

    public void updateProfile(String displayName, String profileImageUrl) {
        this.displayName = displayName;
        this.profileImageUrl = profileImageUrl;
    }

    public Long getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
