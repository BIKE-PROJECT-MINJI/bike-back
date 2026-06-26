package com.bikeprojectminji.bikeback.party.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.party.dto.RidePartyReportResponse;
import com.bikeprojectminji.bikeback.party.entity.RidePartyEntity;
import com.bikeprojectminji.bikeback.party.entity.RidePartyReportEntity;
import com.bikeprojectminji.bikeback.party.entity.RidePartyReportReason;
import com.bikeprojectminji.bikeback.party.entity.RidePartyStatus;
import com.bikeprojectminji.bikeback.party.repository.RidePartyReportRepository;
import com.bikeprojectminji.bikeback.party.repository.RidePartyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RidePartyReportServiceTest {

    @Mock
    private RidePartyRepository partyRepository;

    @Mock
    private RidePartyReportRepository reportRepository;

    @Mock
    private AuthService authService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-26T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("고위험 파티 신고는 1회만으로 파티를 닫는다")
    void highRiskReportCancelsPartyImmediately() {
        RidePartyReportService service = createService();
        UserEntity reporter = user(2L);
        RidePartyEntity party = party(20L, 1L);
        given(authService.findUserBySubject("2")).willReturn(reporter);
        given(partyRepository.findById(20L)).willReturn(Optional.of(party));
        given(reportRepository.existsByPartyIdAndReporterUserId(20L, 2L)).willReturn(false);
        given(reportRepository.countByPartyId(20L)).willReturn(1L);
        given(partyRepository.save(any(RidePartyEntity.class))).willAnswer(invocation -> invocation.getArgument(0));

        RidePartyReportResponse response = service.reportParty("2", 20L, RidePartyReportReason.HARASSMENT_OR_THREAT);

        assertThat(response.reportCount()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(RidePartyStatus.CANCELED.name());
    }

    @Test
    @DisplayName("같은 사용자는 같은 파티를 중복 신고할 수 없다")
    void duplicateReporterIsRejected() {
        RidePartyReportService service = createService();
        UserEntity reporter = user(2L);
        RidePartyEntity party = party(20L, 1L);
        given(authService.findUserBySubject("2")).willReturn(reporter);
        given(partyRepository.findById(20L)).willReturn(Optional.of(party));
        given(reportRepository.existsByPartyIdAndReporterUserId(20L, 2L)).willReturn(true);

        assertThatThrownBy(() -> service.reportParty("2", 20L, RidePartyReportReason.SPAM_OR_COMMERCIAL))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("이미 신고한 파티입니다.");
    }

    private RidePartyReportService createService() {
        return new RidePartyReportService(partyRepository, reportRepository, authService, clock);
    }

    private UserEntity user(Long id) {
        UserEntity user = new UserEntity("external-" + id, "user" + id + "@example.com", "hash", "user" + id, null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private RidePartyEntity party(Long id, Long hostUserId) {
        RidePartyEntity party = new RidePartyEntity(10L, hostUserId, "공개 파티", OffsetDateTime.now(clock), 5);
        ReflectionTestUtils.setField(party, "id", id);
        return party;
    }
}
