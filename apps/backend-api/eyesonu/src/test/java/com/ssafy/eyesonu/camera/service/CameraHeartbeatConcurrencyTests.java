package com.ssafy.eyesonu.camera.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.eyesonu.TestDatabaseConfiguration;
import com.ssafy.eyesonu.auth.device.MediaServerPrincipal;
import com.ssafy.eyesonu.camera.domain.CameraHeartbeatState;
import com.ssafy.eyesonu.camera.dto.device.CameraHeartbeatRequest;
import com.ssafy.eyesonu.camera.mapper.CameraMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.flyway.enabled=true")
@ActiveProfiles("test")
@Import({TestDatabaseConfiguration.class, CameraHeartbeatConcurrencyTests.ClockConfiguration.class})
@Sql(
        scripts = "/recording-fixture.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD,
        config = @SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED))
class CameraHeartbeatConcurrencyTests {

    private static final String CAMERA_CODE = "recording-fixture-camera-153001";
    private static final Long CAMERA_ID = 153001L;
    private static final Long MEDIA_SERVER_ID = 152001L;
    private static final MediaServerPrincipal PRINCIPAL =
            new MediaServerPrincipal(MEDIA_SERVER_ID, "recording-fixture-server-1");

    @Autowired
    private CameraHeartbeatService heartbeatService;

    @Autowired
    private CameraMapper cameraMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ThreadLocalClock clock;

    @Test
    void heartbeatAndOfflineSweepCompleteWithoutDeadlockAndKeepFreshReceiptOnline()
            throws Exception {
        Instant initialHeartbeat = Instant.parse("2099-07-20T02:00:00Z");
        Instant schedulerNow = initialHeartbeat.plusSeconds(31);
        Instant freshHeartbeat = initialHeartbeat.plusSeconds(40);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(ignored -> {
            CameraHeartbeatState initial = cameraMapper
                    .findHeartbeatStateByCameraCode(CAMERA_CODE)
                    .orElseThrow();
            assertThat(cameraMapper.updateHeartbeat(
                    CAMERA_ID,
                    MEDIA_SERVER_ID,
                    "ONLINE",
                    initialHeartbeat,
                    initial.status(),
                    initial.lastHeartbeat())).isEqualTo(1);
        });

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> heartbeat = executor.submit(() -> {
                clock.set(freshHeartbeat);
                ready.countDown();
                try {
                    await(start);
                    heartbeatService.receive(
                            PRINCIPAL,
                            CAMERA_CODE,
                            new CameraHeartbeatRequest(
                                    OffsetDateTime.ofInstant(initialHeartbeat, ZoneOffset.UTC),
                                    "ONLINE",
                                    null));
                } finally {
                    clock.clear();
                }
            });
            Future<Integer> offlineSweep = executor.submit(() -> {
                ready.countDown();
                await(start);
                return heartbeatService.markOffline(schedulerNow, Duration.ofSeconds(30));
            });

            boolean actorsReady = ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            assertThat(actorsReady).isTrue();
            assertThat(offlineSweep.get(10, TimeUnit.SECONDS)).isIn(0, 1);
            heartbeat.get(10, TimeUnit.SECONDS);

            CameraHeartbeatState latest = cameraMapper
                    .findHeartbeatStateByCameraCode(CAMERA_CODE)
                    .orElseThrow();
            assertThat(latest.status()).isEqualTo("ONLINE");
            assertThat(latest.lastHeartbeat()).isEqualTo(freshHeartbeat);
        } finally {
            jdbcTemplate.update("DELETE FROM cameras WHERE id IN (?, ?)", 153001L, 153002L);
            jdbcTemplate.update("DELETE FROM media_servers WHERE id IN (?, ?)", 152001L, 152002L);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent heartbeat test did not start in time");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent heartbeat test was interrupted", exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        @Primary
        ThreadLocalClock heartbeatTestClock() {
            return new ThreadLocalClock();
        }
    }

    static final class ThreadLocalClock extends Clock {

        private static final Instant DEFAULT_INSTANT = Instant.parse("2026-07-20T02:00:00Z");
        private final ThreadLocal<Instant> current = new ThreadLocal<>();

        void set(Instant instant) {
            current.set(instant);
        }

        void clear() {
            current.remove();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            Instant instant = current.get();
            return instant == null ? DEFAULT_INSTANT : instant;
        }
    }
}
