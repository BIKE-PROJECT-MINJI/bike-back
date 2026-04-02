package com.bikeprojectminji.bikeback.service.ride;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.dto.ride.CreateRideRecordRequest;
import com.bikeprojectminji.bikeback.dto.ride.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.dto.ride.RideRecordResponse;
import com.bikeprojectminji.bikeback.dto.ride.RideRecordSummaryRequest;
import com.bikeprojectminji.bikeback.entity.ride.RideRecordEntity;
import com.bikeprojectminji.bikeback.entity.user.UserEntity;
import com.bikeprojectminji.bikeback.repository.ride.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.repository.ride.RideRecordRepository;
import com.bikeprojectminji.bikeback.service.auth.AuthService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RideRecordServiceTest {

    @Mock
    private AuthService authService;

    @Mock
    private RideRecordRepository rideRecordRepository;

    @Mock
    private RideRecordPointRepository rideRecordPointRepository;

    @InjectMocks
    private RideRecordService rideRecordService;

    @Test
    @DisplayName("자유 주행 기록 저장은 소유자와 route point를 함께 저장한다")
    void saveRideRecordReturnsPersistedRecordResponse() {
        UserEntity user = new UserEntity("device-1", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        RideRecordEntity savedRideRecord = new RideRecordEntity(1L, OffsetDateTime.parse("2026-03-29T10:00:00+09:00"), OffsetDateTime.parse("2026-03-29T11:00:00+09:00"), 18250, 3600);
        ReflectionTestUtils.setField(savedRideRecord, "id", 1001L);

        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.save(any(RideRecordEntity.class))).willReturn(savedRideRecord);

        RideRecordResponse response = rideRecordService.saveRideRecord("1", new CreateRideRecordRequest(
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T11:00:00+09:00"),
                new RideRecordSummaryRequest(18250, 3600),
                List.of(
                        new RideRecordPointRequest(1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)),
                        new RideRecordPointRequest(2, BigDecimal.valueOf(37.5671), BigDecimal.valueOf(126.9792))
                )
        ));

        assertThat(response.rideRecordId()).isEqualTo(1001L);
        assertThat(response.ownerUserId()).isEqualTo(1L);
        assertThat(response.routePointCount()).isEqualTo(2);
    }
}
