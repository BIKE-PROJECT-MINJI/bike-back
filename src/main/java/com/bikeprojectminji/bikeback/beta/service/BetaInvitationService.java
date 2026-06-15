package com.bikeprojectminji.bikeback.beta.service;

import com.bikeprojectminji.bikeback.beta.dto.BetaInvitationVerifyResponse;
import com.bikeprojectminji.bikeback.beta.entity.BetaInvitationCodeEntity;
import com.bikeprojectminji.bikeback.beta.repository.BetaInvitationCodeRepository;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.ConflictException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BetaInvitationService {

    private final BetaInvitationCodeRepository betaInvitationCodeRepository;
    private final Clock clock;

    public BetaInvitationService(BetaInvitationCodeRepository betaInvitationCodeRepository, Clock clock) {
        this.betaInvitationCodeRepository = betaInvitationCodeRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BetaInvitationVerifyResponse verify(String code) {
        BetaInvitationCodeEntity invitationCode = findUsableCode(code);
        return new BetaInvitationVerifyResponse(true, invitationCode.getExpiresAt());
    }

    @Transactional
    public void consumeForUser(String code, Long userId) {
        BetaInvitationCodeEntity invitationCode = findUsableCode(code);
        invitationCode.markUsed(userId, clock);
    }

    private BetaInvitationCodeEntity findUsableCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BadRequestException("초대 코드는 비어 있을 수 없습니다.");
        }
        BetaInvitationCodeEntity invitationCode = betaInvitationCodeRepository.findByCode(normalizeCode(code))
                .orElseThrow(() -> new NotFoundException("초대 코드를 찾을 수 없습니다."));
        if (invitationCode.isUsed()) {
            throw new ConflictException("이미 사용된 초대 코드입니다.");
        }
        if (invitationCode.isExpired(clock)) {
            throw new BadRequestException("만료된 초대 코드입니다.");
        }
        return invitationCode;
    }

    private String normalizeCode(String code) {
        return code.trim().toUpperCase();
    }
}
