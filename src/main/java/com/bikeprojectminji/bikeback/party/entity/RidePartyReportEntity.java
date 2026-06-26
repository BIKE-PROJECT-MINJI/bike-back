package com.bikeprojectminji.bikeback.party.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ride_party_reports")
public class RidePartyReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "party_id", nullable = false)
    private Long partyId;

    @Column(name = "reporter_user_id", nullable = false)
    private Long reporterUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 60)
    private RidePartyReportReason reason;

    @Column(name = "reported_at", nullable = false)
    private OffsetDateTime reportedAt;

    protected RidePartyReportEntity() {
    }

    private RidePartyReportEntity(Long partyId, Long reporterUserId, RidePartyReportReason reason, Clock clock) {
        if (partyId == null || partyId <= 0) {
            throw new IllegalArgumentException("partyId는 양수여야 합니다.");
        }
        if (reporterUserId == null || reporterUserId <= 0) {
            throw new IllegalArgumentException("reporterUserId는 양수여야 합니다.");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason은 비어 있을 수 없습니다.");
        }
        this.partyId = partyId;
        this.reporterUserId = reporterUserId;
        this.reason = reason;
        this.reportedAt = OffsetDateTime.now(clock);
    }

    public static RidePartyReportEntity create(Long partyId, Long reporterUserId, RidePartyReportReason reason, Clock clock) {
        return new RidePartyReportEntity(partyId, reporterUserId, reason, clock);
    }

    public Long getId() {
        return id;
    }

    public Long getPartyId() {
        return partyId;
    }

    public Long getReporterUserId() {
        return reporterUserId;
    }

    public RidePartyReportReason getReason() {
        return reason;
    }

    public OffsetDateTime getReportedAt() {
        return reportedAt;
    }
}
