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
@Table(name = "ride_party_members")
public class RidePartyMemberEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "party_id", nullable = false)
    private Long partyId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RidePartyMemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RidePartyMemberStatus status;

    @Column(name = "joined_at", nullable = false)
    private OffsetDateTime joinedAt;

    @Column(name = "left_at")
    private OffsetDateTime leftAt;

    protected RidePartyMemberEntity() {
    }

    public RidePartyMemberEntity(Long partyId, Long userId, RidePartyMemberRole role, Clock clock) {
        this.partyId = partyId;
        this.userId = userId;
        this.role = role;
        this.status = RidePartyMemberStatus.JOINED;
        this.joinedAt = OffsetDateTime.now(clock);
    }

    public void rejoin(Clock clock) {
        this.status = RidePartyMemberStatus.JOINED;
        this.joinedAt = OffsetDateTime.now(clock);
        this.leftAt = null;
    }

    public void leave(Clock clock) {
        this.status = RidePartyMemberStatus.LEFT;
        this.leftAt = OffsetDateTime.now(clock);
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

    public RidePartyMemberRole getRole() {
        return role;
    }

    public RidePartyMemberStatus getStatus() {
        return status;
    }

    public OffsetDateTime getJoinedAt() {
        return joinedAt;
    }
}
