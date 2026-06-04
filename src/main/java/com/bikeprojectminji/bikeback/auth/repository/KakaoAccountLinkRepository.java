package com.bikeprojectminji.bikeback.auth.repository;

import com.bikeprojectminji.bikeback.auth.entity.KakaoAccountLinkEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KakaoAccountLinkRepository extends JpaRepository<KakaoAccountLinkEntity, Long> {

    Optional<KakaoAccountLinkEntity> findByProviderUserId(String providerUserId);

    Optional<KakaoAccountLinkEntity> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
