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
@Table(name = "user_consents")
public class UserConsentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "privacy_policy_version", nullable = false, length = 80)
    private String privacyPolicyVersion;

    @Column(name = "terms_version", nullable = false, length = 80)
    private String termsVersion;

    @Column(name = "location_terms_version", nullable = false, length = 80)
    private String locationTermsVersion;

    @Column(name = "age_verified", nullable = false)
    private boolean ageVerified;

    @Column(name = "age_band", nullable = false, length = 20)
    private String ageBand;

    @Column(name = "age_verified_at", nullable = false)
    private OffsetDateTime ageVerifiedAt;

    @Column(name = "consented_at", nullable = false)
    private OffsetDateTime consentedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected UserConsentEntity() {
    }

    public UserConsentEntity(Long userId, String privacyPolicyVersion, String termsVersion, String locationTermsVersion, Clock clock) {
        this(userId, privacyPolicyVersion, termsVersion, locationTermsVersion, "ADULT", clock);
    }

    public UserConsentEntity(Long userId, String privacyPolicyVersion, String termsVersion, String locationTermsVersion, String ageBand, Clock clock) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        this.userId = userId;
        this.privacyPolicyVersion = privacyPolicyVersion;
        this.termsVersion = termsVersion;
        this.locationTermsVersion = locationTermsVersion;
        this.ageVerified = true;
        this.ageBand = ageBand;
        this.ageVerifiedAt = now;
        this.consentedAt = now;
        this.updatedAt = now;
    }

    public void updateVersions(String privacyPolicyVersion, String termsVersion, String locationTermsVersion, String ageBand, Clock clock) {
        this.privacyPolicyVersion = privacyPolicyVersion;
        this.termsVersion = termsVersion;
        this.locationTermsVersion = locationTermsVersion;
        this.ageVerified = true;
        this.ageBand = ageBand;
        this.ageVerifiedAt = OffsetDateTime.now(clock);
        this.updatedAt = OffsetDateTime.now(clock);
    }

    public void updateVersions(String privacyPolicyVersion, String termsVersion, String locationTermsVersion, Clock clock) {
        updateVersions(privacyPolicyVersion, termsVersion, locationTermsVersion, this.ageBand == null ? "ADULT" : this.ageBand, clock);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getPrivacyPolicyVersion() {
        return privacyPolicyVersion;
    }

    public String getTermsVersion() {
        return termsVersion;
    }

    public String getLocationTermsVersion() {
        return locationTermsVersion;
    }

    public boolean isAgeVerified() {
        return ageVerified;
    }

    public String getAgeBand() {
        return ageBand;
    }

    public OffsetDateTime getAgeVerifiedAt() {
        return ageVerifiedAt;
    }

    public OffsetDateTime getConsentedAt() {
        return consentedAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
