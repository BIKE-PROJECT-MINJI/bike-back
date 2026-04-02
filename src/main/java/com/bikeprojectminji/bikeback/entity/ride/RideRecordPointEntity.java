package com.bikeprojectminji.bikeback.entity.ride;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

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

    protected RideRecordPointEntity() {
    }

    public RideRecordPointEntity(Long rideRecordId, Integer pointOrder, BigDecimal latitude, BigDecimal longitude) {
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
