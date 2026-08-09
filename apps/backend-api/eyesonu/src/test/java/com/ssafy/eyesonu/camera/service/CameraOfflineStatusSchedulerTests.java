package com.ssafy.eyesonu.camera.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CameraOfflineStatusSchedulerTests {

    @Test
    void passesConfiguredOfflineTimeoutToStatusCheck() {
        CameraHeartbeatService heartbeatService = mock(CameraHeartbeatService.class);
        CameraHeartbeatProperties properties = new CameraHeartbeatProperties();
        properties.setOfflineTimeoutMs(45_000);
        properties.setStatusCheckIntervalMs(5_000);
        Instant now = Instant.parse("2026-07-20T02:01:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        CameraOfflineStatusScheduler scheduler =
                new CameraOfflineStatusScheduler(heartbeatService, properties, clock);

        scheduler.markTimedOutCamerasOffline();

        verify(heartbeatService).markOffline(eq(now), eq(Duration.ofSeconds(45)));
    }
}
