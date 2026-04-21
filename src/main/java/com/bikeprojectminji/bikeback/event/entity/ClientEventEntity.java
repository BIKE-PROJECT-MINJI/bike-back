package com.bikeprojectminji.bikeback.event.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "client_events")
public class ClientEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_name", nullable = false, length = 100)
    private String eventName;

    @Column(name = "event_version", nullable = false)
    private Integer eventVersion;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(name = "occurred_at_client")
    private OffsetDateTime occurredAtClient;

    @Column(name = "received_at_server", nullable = false)
    private OffsetDateTime receivedAtServer;

    @Column(name = "screen_name", length = 100)
    private String screenName;

    @Column(name = "course_id")
    private Long courseId;

    @Column(name = "ride_record_id")
    private Long rideRecordId;

    @Column(name = "app_version", length = 50)
    private String appVersion;

    @Column(name = "os_name", length = 50)
    private String osName;

    @Column(name = "device_type", length = 50)
    private String deviceType;

    @Column(name = "location_permission_state", length = 50)
    private String locationPermissionState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "properties_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode propertiesJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ClientEventEntity() {
    }

    public ClientEventEntity(
            String eventName,
            Integer eventVersion,
            Long userId,
            String sessionId,
            OffsetDateTime occurredAtClient,
            OffsetDateTime receivedAtServer,
            String screenName,
            Long courseId,
            Long rideRecordId,
            String appVersion,
            String osName,
            String deviceType,
            String locationPermissionState,
            JsonNode propertiesJson,
            OffsetDateTime createdAt
    ) {
        this.eventName = eventName;
        this.eventVersion = eventVersion;
        this.userId = userId;
        this.sessionId = sessionId;
        this.occurredAtClient = occurredAtClient;
        this.receivedAtServer = receivedAtServer;
        this.screenName = screenName;
        this.courseId = courseId;
        this.rideRecordId = rideRecordId;
        this.appVersion = appVersion;
        this.osName = osName;
        this.deviceType = deviceType;
        this.locationPermissionState = locationPermissionState;
        this.propertiesJson = propertiesJson;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public OffsetDateTime getReceivedAtServer() {
        return receivedAtServer;
    }

    public JsonNode getPropertiesJson() {
        return propertiesJson;
    }
}
