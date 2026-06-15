package com.bikeprojectminji.bikeback.beta.repository;

import com.bikeprojectminji.bikeback.beta.entity.BetaInvitationCodeEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BetaInvitationCodeRepository extends JpaRepository<BetaInvitationCodeEntity, Long> {

    Optional<BetaInvitationCodeEntity> findByCode(String code);
}
