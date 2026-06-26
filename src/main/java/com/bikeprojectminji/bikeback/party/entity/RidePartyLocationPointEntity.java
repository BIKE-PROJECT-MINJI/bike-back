package com.bikeprojectminji.bikeback.party.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ride_party_location_points")
public class RidePartyLocationPointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "party_id", nullable = false)
    private Long partyId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "accuracy_m", precision = 8, scale = 2)
    private BigDecimal accuracyM;

    @Column(name = "speed_mps", precision = 8, scale = 2)
    private BigDecimal speedMps;

    @Column(name = "bearing_deg", precision = 6, scale = 2)
    private BigDecimal bearingDeg;

    @Column(name = "captured_at", nullable = false)
    private OffsetDateTime capturedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected RidePartyLocationPointEntity() {
    }

    private RidePartyLocationPointEntity(
            Long partyId,
            Long userId,
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal accuracyM,
            BigDecimal speedMps,
            BigDecimal bearingDeg,
            OffsetDateTime capturedAt,
            Clock clock
    ) {
        this.partyId = partyId;
        this.userId = userId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyM = accuracyM;
        this.speedMps = speedMps;
        this.bearingDeg = bearingDeg;
        this.capturedAt = capturedAt == null ? OffsetDateTime.now(clock) : capturedAt;
        this.createdAt = OffsetDateTime.now(clock);
    }

    public static RidePartyLocationPointEntity create(
            Long partyId,
            Long userId,
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal accuracyM,
            BigDecimal speedMps,
            BigDecimal bearingDeg,
            OffsetDateTime capturedAt,
            Clock clock
    ) {
        return new RidePartyLocationPointEntity(
                partyId,
                userId,
                latitude,
                longitude,
                accuracyM,
                speedMps,
                bearingDeg,
                capturedAt,
                clock
        );
    }

    public Long getId() {
        return id;
    }

    public Long getPartyId() {
        return partyId;
    }

    public Long getUserId() {
        return userId;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public OffsetDateTime getCapturedAt() {
        return capturedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
