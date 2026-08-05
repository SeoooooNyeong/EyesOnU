package com.ssafy.eyesonu.missingcase.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.eyesonu.TestDatabaseConfiguration;
import com.ssafy.eyesonu.missingcase.domain.AdminCaseRouteRow;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.flyway.enabled=true")
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
@Transactional
@Sql(
        scripts = "/recording-fixture.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD,
        config = @SqlConfig(transactionMode = SqlConfig.TransactionMode.INFERRED))
class AdminCaseRouteMapperTest {
    private static final long CASE_ID = 159001L;

    @Autowired
    private AdminCaseRouteMapper routeMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void insertRouteFixture() {
        jdbcTemplate.update("""
                INSERT INTO reporters (id, name, phone, relation)
                VALUES (?, '동선 테스트 신고자', '01015900001', '보호자')
                """, 159001L);
        jdbcTemplate.update("""
                INSERT INTO admins (id, login_id, password_hash, name)
                VALUES (?, 'route-test-admin', 'route-test-password-hash', '동선 테스트 관리자')
                """, 159001L);
        jdbcTemplate.update("""
                INSERT INTO cases (
                    id, reporter_id, case_number, report_content, missing_name, gender,
                    distinctive_features, last_seen_time, last_seen_address
                ) VALUES (?, ?, 'ROUTE-MAPPER-159001', '동선 매퍼 테스트', '김동선', 'UNKNOWN',
                          '파란 상의', '2026-08-05 08:00:00.000000', '서울시 테스트구')
                """, CASE_ID, 159001L);

        insertCandidate(159004L, 153002L, "route-track-4", "2026-08-05 10:05:00.000000", "CONFIRMED");
        insertCandidate(159003L, 153001L, "route-track-3", "2026-08-05 09:00:00.000000", "REJECTED");
        insertCandidate(159002L, 153001L, "route-track-2", "2026-08-05 10:00:00.000000", "CONFIRMED");
        insertCandidate(159001L, 153001L, "route-track-1", "2026-08-05 10:00:00.000000", "CONFIRMED");
    }

    @Test
    void returnsOnlyConfirmedCandidatesOrderedByFirstDetectionThenId() {
        List<AdminCaseRouteRow> rows = routeMapper.findConfirmedRoutePoints(CASE_ID);

        assertThat(rows).extracting(AdminCaseRouteRow::candidateId)
                .containsExactly(159001L, 159002L, 159004L);
        assertThat(rows.getFirst().cameraCode()).isEqualTo("recording-fixture-camera-153001");
        assertThat(rows.getFirst().address()).isEqualTo("Recording Mapper Fixture Address 1");
        assertThat(rows.getFirst().latitude().toPlainString()).isEqualTo("37.5665000");
        assertThat(rows.getLast().longitude().toPlainString()).isEqualTo("126.9781000");
    }

    private void insertCandidate(long id, long cameraId, String trackId, String detectedAt, String reviewStatus) {
        jdbcTemplate.update("""
                INSERT INTO candidates (
                    id, case_id, camera_id, detected_time, similarity, crop_object_key, track_id,
                    first_detected_at, last_detected_at, best_similarity, average_similarity,
                    detection_count, review_status, reviewed_by, reviewed_at
                ) VALUES (?, ?, ?, ?, 0.9000, ?, ?, ?, ?, 0.9200, 0.9000, 2, ?, ?, ?)
                """,
                id, CASE_ID, cameraId, detectedAt, "route/" + id + ".jpg", trackId,
                detectedAt, detectedAt, reviewStatus, 159001L, detectedAt);
    }
}
