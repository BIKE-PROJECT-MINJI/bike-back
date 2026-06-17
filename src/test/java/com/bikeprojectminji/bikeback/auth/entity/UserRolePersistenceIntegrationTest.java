package com.bikeprojectminji.bikeback.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.auth.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class UserRolePersistenceIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("사용자 role은 user_roles 테이블에 저장되고 다시 조회된다")
    void userRolesAreStoredAndLoaded() {
        UserEntity user = new UserEntity("role-user", "role@example.com", "hash", "role-rider", null);
        user.grantRole(UserRole.OPS_ADMIN);
        UserEntity savedUser = userRepository.saveAndFlush(user);
        entityManager.clear();

        UserEntity loadedUser = userRepository.findById(savedUser.getId()).orElseThrow();

        assertThat(loadedUser.getRoles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.OPS_ADMIN);
    }
}
