package com.ssafy.eyesonu.camera.service;

import java.time.Clock;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CameraOfflineStatusScheduler {

    private final CameraHeartbeatService heartbeatService;
    private final Duration offlineTimeout;
    private final Clock clock;

    public CameraOfflineStatusScheduler(
            CameraHeartbeatService heartbeatService,
            CameraHeartbeatProperties properties,
            Clock clock) {
        this.heartbeatService = heartbeatService;
        this.offlineTimeout = properties.getOfflineTimeout();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${camera.heartbeat.status-check-interval-ms:10000}")
    public void markTimedOutCamerasOffline() {
        heartbeatService.markOffline(clock.instant(), offlineTimeout);
    }
}
