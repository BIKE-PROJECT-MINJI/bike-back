package com.bikeprojectminji.bikeback.beta.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BetaAccessPolicyTest {

    private final BetaAccessPolicy betaAccessPolicy = new BetaAccessPolicy();

    @Test
    @DisplayName("beta 권한이 있는 사용자는 beta 전용 기능 guard를 통과한다")
    void assertBetaAccessAllowsGrantedUser() {
        UserEntity user = new UserEntity("external", "beta@example.com", "hash", "beta-rider", null);
        user.grantBetaAccess();

        assertThatCode(() -> betaAccessPolicy.assertBetaAccess(user))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("beta 권한이 없는 사용자는 beta 전용 기능 guard에서 403으로 거부된다")
    void assertBetaAccessRejectsUserWithoutBetaAccess() {
        UserEntity user = new UserEntity("external", "regular@example.com", "hash", "regular-rider", null);

        assertThatThrownBy(() -> betaAccessPolicy.assertBetaAccess(user))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("베타 초대 권한이 필요합니다.");
    }
}
