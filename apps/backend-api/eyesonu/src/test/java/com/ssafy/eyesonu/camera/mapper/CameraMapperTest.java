package com.ssafy.eyesonu.camera.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ssafy.eyesonu.TestDatabaseConfiguration;
import com.ssafy.eyesonu.camera.domain.CameraCreateCommand;
import com.ssafy.eyesonu.camera.domain.CameraHeartbeatState;
import com.ssafy.eyesonu.camera.domain.CameraManagementRow;
import com.ssafy.eyesonu.camera.domain.CameraStreamUrlRow;
import com.ssafy.eyesonu.camera.domain.CameraUpdateCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.flyway.enabled=true")
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
@Transactional
@Sql(
        scripts = "/recording-fixture.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD,
        config = @SqlConfig(transactionMode = SqlConfig.TransactionMode.INFERRED))
class CameraMapperTest {

    @Autowired
    private CameraMapper cameraMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void adminLookupJoinsMediaServerAndKeepsInternalRtspValueAvailableToService() {
        CameraManagementRow row = cameraMapper.findAdminById(153001L);

        assertThat(row).isNotNull();
        assertThat(row.mediaServerId()).isEqualTo(152001L);
        assertThat(row.mediaServerCode()).isEqualTo("recording-fixture-server-1");
        assertThat(row.cameraCode()).isEqualTo("recording-fixture-camera-153001");
        assertThat(row.rtspUrl()).isEqualTo("rtsp://recording-fixture/153001/stream");
        assertThat(cameraMapper.findAdminByIdForUpdate(153001L).cameraCode())
                .isEqualTo(row.cameraCode());
    }

    @Test
    void findsStreamUrlWithoutLoadingTheFullCameraProjection() {
        CameraStreamUrlRow row = cameraMapper.findStreamUrlById(153001L);

        assertThat(row).isNotNull();
        assertThat(row.id()).isEqualTo(153001L);
        assertThat(row.streamUrl()).isEqualTo("rtsp://recording-fixture/153001/stream");
        assertThat(cameraMapper.findStreamUrlById(999999L)).isNull();
    }

    @Test
    void adminPageFiltersAndUsesStableAllowedSort() {
        List<CameraManagementRow> rows = cameraMapper.findAdminPage(
                "OFFLINE", "15300", "camera_code", "ASC", 20, 0);

        assertThat(rows).extracting(CameraManagementRow::cameraCode)
                .containsExactly(
                        "recording-fixture-camera-153001",
                        "recording-fixture-camera-153002");
        assertEquals(2L, cameraMapper.countAdminCameras("OFFLINE", "fixture"));
    }

    @Test
    void insertAndUpdateKeepImmutableAndOperationalFieldsOutsideUpdateCommand() {
        CameraCreateCommand create = new CameraCreateCommand(
                152001L,
                "camera-mapper-created",
                "Created Camera",
                new BigDecimal("37.5000000"),
                new BigDecimal("127.0000000"),
                "Created Address",
                "rtsp://created/stream");
        assertEquals(1, cameraMapper.insert(create));
        assertThat(create.getId()).isPositive();

        CameraManagementRow created = cameraMapper.findAdminById(create.getId());
        assertThat(created.status()).isEqualTo("OFFLINE");
        assertThat(created.lastHeartbeat()).isNull();

        assertEquals(1, cameraMapper.updateDetails(new CameraUpdateCommand(
                create.getId(),
                152002L,
                "Updated Camera",
                new BigDecimal("37.6000000"),
                new BigDecimal("127.1000000"),
                "Updated Address",
                "rtsp://updated/stream")));
        CameraManagementRow updated = cameraMapper.findAdminById(create.getId());
        assertThat(updated.cameraCode()).isEqualTo("camera-mapper-created");
        assertThat(updated.status()).isEqualTo("OFFLINE");
        assertThat(updated.lastHeartbeat()).isNull();
        assertThat(updated.mediaServerId()).isEqualTo(152002L);
    }

    @Test
    void heartbeatMapperPreservesNewestReceiptAndSupportsLivenessTransitions() {
        String cameraCode = "recording-fixture-camera-153001";
        Instant onlineReceivedAt = Instant.parse("2026-07-20T02:00:00Z");
        CameraHeartbeatState initial = cameraMapper
                .findHeartbeatStateByCameraCode(cameraCode)
                .orElseThrow();
        Instant initialUpdatedAt = cameraMapper.findAdminById(initial.id()).updatedAt();

        assertThat(initial.status()).isEqualTo("OFFLINE");
        assertThat(cameraMapper.updateHeartbeat(
                initial.id(),
                initial.mediaServerId(),
                "ONLINE",
                onlineReceivedAt,
                "OFFLINE",
                null)).isEqualTo(1);
        assertThat(cameraMapper.updateHeartbeat(
                initial.id(),
                initial.mediaServerId(),
                "ERROR",
                onlineReceivedAt.minusSeconds(1),
                "ONLINE",
                onlineReceivedAt)).isZero();
        assertThat(cameraMapper.updateHeartbeat(
                initial.id(),
                initial.mediaServerId(),
                "ERROR",
                onlineReceivedAt.plusSeconds(1),
                "OFFLINE",
                null)).isZero();
        assertThat(cameraMapper.updateHeartbeat(
                initial.id(),
                999999L,
                "ERROR",
                onlineReceivedAt.plusSeconds(1),
                "ONLINE",
                onlineReceivedAt)).isZero();

        CameraHeartbeatState online = cameraMapper
                .findHeartbeatStateByCameraCode(cameraCode)
                .orElseThrow();
        assertThat(online.status()).isEqualTo("ONLINE");
        assertThat(online.lastHeartbeat()).isEqualTo(onlineReceivedAt);
        assertThat(cameraMapper.findAdminById(initial.id()).updatedAt())
                .isAfter(initialUpdatedAt);

        Instant firstErrorReceivedAt = onlineReceivedAt.plusSeconds(10);
        Instant repeatedErrorReceivedAt = firstErrorReceivedAt.plusSeconds(10);
        assertThat(cameraMapper.updateHeartbeat(
                initial.id(),
                initial.mediaServerId(),
                "ERROR",
                firstErrorReceivedAt,
                "ONLINE",
                onlineReceivedAt)).isEqualTo(1);
        assertThat(cameraMapper.updateHeartbeat(
                initial.id(),
                initial.mediaServerId(),
                "ERROR",
                repeatedErrorReceivedAt,
                "ERROR",
                firstErrorReceivedAt)).isEqualTo(1);
        assertThat(cameraMapper.updateHeartbeat(
                initial.id(),
                initial.mediaServerId(),
                "ONLINE",
                firstErrorReceivedAt,
                "ERROR",
                repeatedErrorReceivedAt)).isZero();

        CameraHeartbeatState error = cameraMapper
                .findHeartbeatStateByCameraCode(cameraCode)
                .orElseThrow();
        assertThat(error.status()).isEqualTo("ERROR");
        assertThat(error.lastHeartbeat()).isEqualTo(repeatedErrorReceivedAt);

        assertThat(cameraMapper.markOffline(repeatedErrorReceivedAt)).isZero();
        Instant afterTimeoutThreshold = repeatedErrorReceivedAt.plusNanos(1_000);
        assertThat(cameraMapper.markOffline(afterTimeoutThreshold)).isEqualTo(1);
        assertThat(cameraMapper.markOffline(afterTimeoutThreshold)).isZero();

        CameraHeartbeatState offline = cameraMapper
                .findHeartbeatStateByCameraCode(cameraCode)
                .orElseThrow();
        assertThat(offline.status()).isEqualTo("OFFLINE");
        assertThat(offline.lastHeartbeat()).isEqualTo(repeatedErrorReceivedAt);

        Instant recoveredAt = repeatedErrorReceivedAt.plusSeconds(40);
        assertThat(cameraMapper.updateHeartbeat(
                initial.id(),
                initial.mediaServerId(),
                "ONLINE",
                recoveredAt,
                "ERROR",
                repeatedErrorReceivedAt)).isZero();
        assertThat(cameraMapper.updateHeartbeat(
                initial.id(),
                initial.mediaServerId(),
                "ONLINE",
                recoveredAt,
                "OFFLINE",
                repeatedErrorReceivedAt)).isEqualTo(1);
        CameraHeartbeatState recovered = cameraMapper
                .findHeartbeatStateByCameraCode(cameraCode)
                .orElseThrow();
        assertThat(recovered.status()).isEqualTo("ONLINE");
        assertThat(recovered.lastHeartbeat()).isEqualTo(recoveredAt);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentHeartbeatReadsDoNotPrelockAndNewestReceiptWins() throws Exception {
        String cameraCode = "recording-fixture-camera-153001";
        Long cameraId = 153001L;
        Long mediaServerId = 152001L;
        Instant olderReceivedAt = Instant.parse("2099-07-20T02:00:00Z");
        Instant newerReceivedAt = olderReceivedAt.plusSeconds(1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch startOlderUpdate = new CountDownLatch(1);
        CountDownLatch startNewerUpdate = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> olderUpdate = executor.submit(() -> updateAfterSignal(
                    ready,
                    startOlderUpdate,
                    cameraCode,
                    cameraId,
                    mediaServerId,
                    "ERROR",
                    olderReceivedAt));
            Future<Integer> newerUpdate = executor.submit(() -> updateAfterSignal(
                    ready,
                    startNewerUpdate,
                    cameraCode,
                    cameraId,
                    mediaServerId,
                    "ONLINE",
                    newerReceivedAt));

            boolean concurrentReadsReady = ready.await(10, TimeUnit.SECONDS);
            if (!concurrentReadsReady) {
                startOlderUpdate.countDown();
                startNewerUpdate.countDown();
            }
            assertThat(concurrentReadsReady).isTrue();

            startOlderUpdate.countDown();
            assertThat(olderUpdate.get(10, TimeUnit.SECONDS)).isEqualTo(1);

            startNewerUpdate.countDown();
            assertThat(newerUpdate.get(10, TimeUnit.SECONDS)).isEqualTo(1);

            CameraHeartbeatState latest = cameraMapper
                    .findHeartbeatStateByCameraCode(cameraCode)
                    .orElseThrow();
            assertThat(latest.status()).isEqualTo("ONLINE");
            assertThat(latest.lastHeartbeat()).isEqualTo(newerReceivedAt);
        } finally {
            jdbcTemplate.update("DELETE FROM cameras WHERE id IN (?, ?)", 153001L, 153002L);
            jdbcTemplate.update("DELETE FROM media_servers WHERE id IN (?, ?)", 152001L, 152002L);
        }
    }

    private int updateAfterSignal(
            CountDownLatch ready,
            CountDownLatch start,
            String cameraCode,
            Long cameraId,
            Long mediaServerId,
            String status,
            Instant receivedAt) throws InterruptedException {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Integer result = transaction.execute(ignored -> {
            CameraHeartbeatState expected = cameraMapper
                    .findHeartbeatStateByCameraCode(cameraCode)
                    .orElseThrow();
            ready.countDown();
            try {
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent heartbeat test did not start in time");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Concurrent heartbeat test was interrupted", exception);
            }

            int updated = cameraMapper.updateHeartbeat(
                    cameraId,
                    mediaServerId,
                    status,
                    receivedAt,
                    expected.status(),
                    expected.lastHeartbeat());
            if (updated == 1) {
                return 1;
            }

            CameraHeartbeatState latest = cameraMapper
                    .findHeartbeatStateByCameraCodeForUpdate(cameraCode)
                    .orElseThrow();
            if (latest.lastHeartbeat() != null
                    && !receivedAt.isAfter(latest.lastHeartbeat())) {
                return 0;
            }

            return cameraMapper.updateHeartbeat(
                    cameraId,
                    mediaServerId,
                    status,
                    receivedAt,
                    latest.status(),
                    latest.lastHeartbeat());
        });
        if (result == null) {
            throw new IllegalStateException("Concurrent heartbeat transaction returned no result");
        }
        return result;
    }
}
