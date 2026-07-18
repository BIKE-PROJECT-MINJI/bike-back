package com.bikeprojectminji.bikeback.party.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RidePartyLocationAccessIntegrationTest {

    private static final long COURSE_ID = 90_010L;
    private static final long PARTY_ID = 90_020L;
    private static final long USER_ID = 90_002L;

    @Autowired
    private RidePartyLocationAccessService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("""
                insert into courses (
                    id, title, distance_km, estimated_duration_min, display_order, curated, visibility, report_hidden
                ) values (?, 'party access course', 3.2, 20, 1, false, 'PUBLIC', false)
                """, COURSE_ID);
        jdbcTemplate.update("""
                insert into ride_parties (id, course_id, host_user_id, title, capacity, status)
                values (?, ?, 90001, 'party access', 4, 'RIDING')
                """, PARTY_ID, COURSE_ID);
        jdbcTemplate.update("""
                insert into ride_party_members (party_id, user_id, role, status, joined_at)
                values (?, ?, 'MEMBER', 'JOINED', current_timestamp)
                """, PARTY_ID, USER_ID);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    @DisplayName("단일 exists 조회는 JOINED·RIDING·PUBLIC·미신고 숨김 조건을 모두 적용한다")
    void appliesEveryLocationShareCondition() {
        assertThat(service.canShare(PARTY_ID, USER_ID)).isTrue();

        jdbcTemplate.update("update ride_party_members set status = 'LEFT' where party_id = ?", PARTY_ID);
        assertThat(service.canShare(PARTY_ID, USER_ID)).isFalse();

        jdbcTemplate.update("update ride_party_members set status = 'JOINED' where party_id = ?", PARTY_ID);
        jdbcTemplate.update("update ride_parties set status = 'CANCELED' where id = ?", PARTY_ID);
        assertThat(service.canShare(PARTY_ID, USER_ID)).isFalse();

        jdbcTemplate.update("update ride_parties set status = 'RIDING' where id = ?", PARTY_ID);
        jdbcTemplate.update("update courses set report_hidden = true where id = ?", COURSE_ID);
        assertThat(service.canShare(PARTY_ID, USER_ID)).isFalse();
    }

    private void cleanUp() {
        jdbcTemplate.update("delete from ride_party_members where party_id = ?", PARTY_ID);
        jdbcTemplate.update("delete from ride_parties where id = ?", PARTY_ID);
        jdbcTemplate.update("delete from courses where id = ?", COURSE_ID);
    }
}
