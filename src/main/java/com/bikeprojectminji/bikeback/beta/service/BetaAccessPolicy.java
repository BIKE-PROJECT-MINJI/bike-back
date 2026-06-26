package com.bikeprojectminji.bikeback.beta.service;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import org.springframework.stereotype.Component;

@Component
public class BetaAccessPolicy {

    public void assertBetaAccess(UserEntity user) {
        if (hasBetaAccess(user)) {
            return;
        }
        throw new ForbiddenException("베타 초대 권한이 필요합니다.");
    }

    public boolean hasBetaAccess(UserEntity user) {
        return user != null && user.isBetaAccessGranted();
    }
}
