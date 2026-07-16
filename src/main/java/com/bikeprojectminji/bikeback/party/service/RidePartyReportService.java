package com.bikeprojectminji.bikeback.party.service;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.party.dto.RidePartyReportResponse;
import com.bikeprojectminji.bikeback.party.entity.RidePartyEntity;
import com.bikeprojectminji.bikeback.party.entity.RidePartyReportEntity;
import com.bikeprojectminji.bikeback.party.entity.RidePartyReportReason;
import com.bikeprojectminji.bikeback.party.event.RidePartyCanceledEvent;
import com.bikeprojectminji.bikeback.party.repository.RidePartyReportRepository;
import com.bikeprojectminji.bikeback.party.repository.RidePartyRepository;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RidePartyReportService {

    private static final int NORMAL_REPORT_CLOSE_THRESHOLD = 3;

    private final RidePartyRepository partyRepository;
    private final RidePartyReportRepository reportRepository;
    private final AuthService authService;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    public RidePartyReportService(
            RidePartyRepository partyRepository,
            RidePartyReportRepository reportRepository,
            AuthService authService,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.partyRepository = partyRepository;
        this.reportRepository = reportRepository;
        this.authService = authService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public RidePartyReportResponse reportParty(String subject, Long partyId, RidePartyReportReason reason) {
        if (reason == null) {
            throw new BadRequestException("reason은 비어 있을 수 없습니다.");
        }
        UserEntity reporter = authService.findUserBySubject(subject);
        RidePartyEntity party = partyRepository.findById(partyId)
                .orElseThrow(() -> new NotFoundException("파티를 찾을 수 없습니다."));
        if (party.getHostUserId().equals(reporter.getId())) {
            throw new BadRequestException("본인 파티는 신고할 수 없습니다.");
        }
        if (reportRepository.existsByPartyIdAndReporterUserId(party.getId(), reporter.getId())) {
            throw new BadRequestException("이미 신고한 파티입니다.");
        }

        reportRepository.save(RidePartyReportEntity.create(party.getId(), reporter.getId(), reason, clock));
        long reportCount = reportRepository.countByPartyId(party.getId());
        if (shouldClose(reason, reportCount)) {
            party.cancelByReport();
            eventPublisher.publishEvent(new RidePartyCanceledEvent(party.getId()));
        }
        RidePartyEntity savedParty = partyRepository.save(party);
        return new RidePartyReportResponse(savedParty.getId(), reportCount, savedParty.getStatus().name());
    }

    private boolean shouldClose(RidePartyReportReason reason, long reportCount) {
        return reason.isHighRisk() || reportCount >= NORMAL_REPORT_CLOSE_THRESHOLD;
    }
}
