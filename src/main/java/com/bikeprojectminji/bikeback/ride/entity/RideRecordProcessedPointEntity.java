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
@Table(name = "ride_record_processed_points")
public class RideRecordProcessedPointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ride_record_id", nullable = false)
    private Long rideRecordId;

    @Column(name = "point_order", nullable = false)
    private Integer pointOrder;

    @Column(name = "latitude", nullable = false)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false)
    private BigDecimal longitude;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected RideRecordProcessedPointEntity() {
    }

    public RideRecordProcessedPointEntity(Long rideRecordId, Integer pointOrder, BigDecimal latitude, BigDecimal longitude) {
        this.rideRecordId = rideRecordId;
        this.pointOrder = pointOrder;
        this.latitude = latitude;
        this.longitude = longitude;
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
}
