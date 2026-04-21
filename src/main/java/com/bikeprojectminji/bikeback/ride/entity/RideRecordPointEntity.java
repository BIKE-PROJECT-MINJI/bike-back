package com.bikeprojectminji.bikeback.ride.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ride_record_points")
public class RideRecordPointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ride_record_id", nullable = false)
    private Long rideRecordId;

    @Column(name = "point_order", nullable = false)
    private Integer pointOrder;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "captured_at")
    private OffsetDateTime capturedAt;

    @Column(name = "accuracy_m", precision = 8, scale = 2)
    private BigDecimal accuracyM;

    @Column(name = "speed_mps", precision = 8, scale = 2)
    private BigDecimal speedMps;

    @Column(name = "bearing_deg", precision = 6, scale = 2)
    private BigDecimal bearingDeg;

    @Column(name = "altitude_m", precision = 8, scale = 2)
    private BigDecimal altitudeM;

    @Column(name = "distance_to_route_m", precision = 8, scale = 2)
    private BigDecimal distanceToRouteM;

    @Column(name = "route_progress_pct", precision = 5, scale = 2)
    private BigDecimal routeProgressPct;

    protected RideRecordPointEntity() {
    }

    public RideRecordPointEntity(Long rideRecordId, Integer pointOrder, BigDecimal latitude, BigDecimal longitude) {
        this(rideRecordId, pointOrder, latitude, longitude, null, null, null, null, null, null, null);
    }

    public RideRecordPointEntity(
            Long rideRecordId,
            Integer pointOrder,
            BigDecimal latitude,
            BigDecimal longitude,
            OffsetDateTime capturedAt,
            BigDecimal accuracyM,
            BigDecimal speedMps,
            BigDecimal bearingDeg,
            BigDecimal altitudeM,
            BigDecimal distanceToRouteM,
            BigDecimal routeProgressPct
    ) {
        this.rideRecordId = rideRecordId;
        this.pointOrder = pointOrder;
        this.latitude = latitude;
        this.longitude = longitude;
        this.capturedAt = capturedAt;
        this.accuracyM = accuracyM;
        this.speedMps = speedMps;
        this.bearingDeg = bearingDeg;
        this.altitudeM = altitudeM;
        this.distanceToRouteM = distanceToRouteM;
        this.routeProgressPct = routeProgressPct;
    }

    public Long getRideRecordId() {
        return rideRecordId;
    }

    public Integer getPointOrder() {
        return pointOrder;
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

    public BigDecimal getAccuracyM() {
        return accuracyM;
    }
}
