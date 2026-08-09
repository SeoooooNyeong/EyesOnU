package com.ssafy.eyesonu.camera.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.eyesonu.auth.device.MediaServerPrincipal;
import com.ssafy.eyesonu.camera.domain.CameraHeartbeatState;
import com.ssafy.eyesonu.camera.dto.device.CameraHeartbeatRequest;
import com.ssafy.eyesonu.camera.mapper.CameraMapper;
import com.ssafy.eyesonu.common.exception.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CameraHeartbeatServiceTests {

    private static final Long CAMERA_ID = 10L;
    private static final Long MEDIA_SERVER_ID = 20L;
    private static final String CAMERA_CODE = "CAM-001";
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-20T02:05:00Z");
    private static final MediaServerPrincipal PRINCIPAL =
            new MediaServerPrincipal(MEDIA_SERVER_ID, "MS-001");

    private CameraMapper cameraMapper;
    private CameraHeartbeatService service;

    @BeforeEach
    void setUp() {
        cameraMapper = org.mockito.Mockito.mock(CameraMapper.class);
        service = new CameraHeartbeatService(
                cameraMapper, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
    }

    @Test
    void storesServerReceivedAtInsteadOfPastDeviceOccurredAt() {
        Instant occurredAt = Instant.parse("2026-07-20T02:00:00Z");
        when(cameraMapper.findHeartbeatStateByCameraCode(CAMERA_CODE))
                .thenReturn(Optional.of(state("OFFLINE", null)));
        when(cameraMapper.updateHeartbeat(
                CAMERA_ID, MEDIA_SERVER_ID, "ONLINE", RECEIVED_AT, "OFFLINE", null))
                .thenReturn(1);

        service.receive(PRINCIPAL, CAMERA_CODE, request(occurredAt, "ONLINE", null));

        verify(cameraMapper).updateHeartbeat(
                CAMERA_ID, MEDIA_SERVER_ID, "ONLINE", RECEIVED_AT, "OFFLINE", null);
    }

    @Test
    void futureDeviceOccurredAtStillUsesServerReceivedAtForErrorLiveness() {
        Instant occurredAt = Instant.parse("2036-07-20T02:00:00Z");
        when(cameraMapper.findHeartbeatStateByCameraCode(CAMERA_CODE))
                .thenReturn(Optional.of(state("ONLINE", RECEIVED_AT.minusSeconds(10))));
        when(cameraMapper.updateHeartbeat(
                CAMERA_ID,
                MEDIA_SERVER_ID,
                "ERROR",
                RECEIVED_AT,
                "ONLINE",
                RECEIVED_AT.minusSeconds(10)))
                .thenReturn(1);

        service.receive(PRINCIPAL, CAMERA_CODE, request(occurredAt, "ERROR", "camera check failed"));

        verify(cameraMapper).updateHeartbeat(
                CAMERA_ID,
                MEDIA_SERVER_ID,
                "ERROR",
                RECEIVED_AT,
                "ONLINE",
                RECEIVED_AT.minusSeconds(10));
    }

    @Test
    void createsReceivedAtOncePerServiceEntry() {
        Clock clock = org.mockito.Mockito.mock(Clock.class);
        when(clock.instant()).thenReturn(RECEIVED_AT);
        service = new CameraHeartbeatService(cameraMapper, clock);
        when(cameraMapper.findHeartbeatStateByCameraCode(CAMERA_CODE))
                .thenReturn(Optional.of(state("OFFLINE", null)));
        when(cameraMapper.updateHeartbeat(
                CAMERA_ID, MEDIA_SERVER_ID, "ONLINE", RECEIVED_AT, "OFFLINE", null))
                .thenReturn(1);

        service.receive(
                PRINCIPAL,
                CAMERA_CODE,
                request(Instant.parse("2026-07-20T01:00:00Z"), "ONLINE", null));

        verify(clock, times(1)).instant();
    }

    @Test
    void repeatedErrorHeartbeatsKeepAdvancingLiveness() {
        Instant firstReceivedAt = RECEIVED_AT;
        Instant secondReceivedAt = RECEIVED_AT.plusSeconds(10);
        Clock clock = org.mockito.Mockito.mock(Clock.class);
        when(clock.instant()).thenReturn(firstReceivedAt, secondReceivedAt);
        service = new CameraHeartbeatService(cameraMapper, clock);
        when(cameraMapper.findHeartbeatStateByCameraCode(CAMERA_CODE))
                .thenReturn(Optional.of(state("ERROR", RECEIVED_AT.minusSeconds(10))))
                .thenReturn(Optional.of(state("ERROR", firstReceivedAt)));
        when(cameraMapper.updateHeartbeat(
                CAMERA_ID,
                MEDIA_SERVER_ID,
                "ERROR",
                firstReceivedAt,
                "ERROR",
                RECEIVED_AT.minusSeconds(10)))
                .thenReturn(1);
        when(cameraMapper.updateHeartbeat(
                CAMERA_ID,
                MEDIA_SERVER_ID,
                "ERROR",
                secondReceivedAt,
                "ERROR",
                firstReceivedAt))
                .thenReturn(1);

        service.receive(
                PRINCIPAL,
                CAMERA_CODE,
                request(Instant.parse("2026-07-20T01:00:00Z"), "ERROR", "still unhealthy"));
        service.receive(
                PRINCIPAL,
                CAMERA_CODE,
                request(Instant.parse("2026-07-20T01:00:05Z"), "ERROR", "still unhealthy"));

        verify(cameraMapper).updateHeartbeat(
                CAMERA_ID,
                MEDIA_SERVER_ID,
                "ERROR",
                firstReceivedAt,
                "ERROR",
                RECEIVED_AT.minusSeconds(10));
        verify(cameraMapper).updateHeartbeat(
                CAMERA_ID,
                MEDIA_SERVER_ID,
                "ERROR",
                secondReceivedAt,
                "ERROR",
                firstReceivedAt);
    }

    @Test
    void missingCameraReturnsResourceNotFound() {
        when(cameraMapper.findHeartbeatStateByCameraCode(CAMERA_CODE))
                .thenReturn(Optional.empty());

        assertApiError(
                "RESOURCE_NOT_FOUND",
                404,
                () -> service.receive(
                        PRINCIPAL, CAMERA_CODE, request(Instant.now(), "ONLINE", null)));
    }

    @Test
    void foreignCameraReturnsAccessDenied() {
        when(cameraMapper.findHeartbeatStateByCameraCode(CAMERA_CODE))
                .thenReturn(Optional.of(new CameraHeartbeatState(
                        CAMERA_ID, 999L, CAMERA_CODE, "OFFLINE", null)));

        assertApiError(
                "ACCESS_DENIED",
                403,
                () -> service.receive(
                        PRINCIPAL, CAMERA_CODE, request(Instant.now(), "ONLINE", null)));
        verify(cameraMapper, never()).updateHeartbeat(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void olderServerReceivedAtDoesNotOverwriteNewerStateRegardlessOfOccurredAt() {
        Instant latest = RECEIVED_AT.plusSeconds(10);
        when(cameraMapper.findHeartbeatStateByCameraCode(CAMERA_CODE))
                .thenReturn(Optional.of(state("ONLINE", latest)));

        service.receive(
                PRINCIPAL,
                CAMERA_CODE,
                request(Instant.parse("2036-07-20T02:00:00Z"), "ERROR", "late processing"));

        verify(cameraMapper, never()).updateHeartbeat(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsOfflineAsDeviceReportedStatusBecauseTimeoutOwnsOfflineTransition() {
        assertApiError(
                "VALIDATION_ERROR",
                400,
                () -> service.receive(
                        PRINCIPAL,
                        CAMERA_CODE,
                        request(Instant.parse("2026-07-20T02:00:00Z"), "OFFLINE", null)));
        verify(cameraMapper, never())
                .findHeartbeatStateByCameraCode(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void marksTimedOutCamerasWithOneIdempotentBulkUpdate() {
        Instant now = Instant.parse("2026-07-20T02:01:00Z");
        Instant threshold = Instant.parse("2026-07-20T02:00:30Z");
        when(cameraMapper.markOffline(threshold)).thenReturn(1).thenReturn(0);

        assertEquals(1, service.markOffline(now, Duration.ofSeconds(30)));
        assertEquals(0, service.markOffline(now, Duration.ofSeconds(30)));

        verify(cameraMapper, times(2)).markOffline(threshold);
    }

    @Test
    void classifiesOptimisticUpdateMissAsOlderServerReceipt() {
        Instant latestHeartbeat = RECEIVED_AT.plusSeconds(10);
        when(cameraMapper.findHeartbeatStateByCameraCode(CAMERA_CODE))
                .thenReturn(Optional.of(state("OFFLINE", null)));
        when(cameraMapper.findHeartbeatStateByCameraCodeForUpdate(CAMERA_CODE))
                .thenReturn(Optional.of(state("ONLINE", latestHeartbeat)));
        when(cameraMapper.updateHeartbeat(
                CAMERA_ID, MEDIA_SERVER_ID, "ONLINE", RECEIVED_AT, "OFFLINE", null))
                .thenReturn(0);

        service.receive(
                PRINCIPAL,
                CAMERA_CODE,
                request(Instant.parse("2036-07-20T02:00:00Z"), "ONLINE", null));

        verify(cameraMapper).findHeartbeatStateByCameraCode(CAMERA_CODE);
        verify(cameraMapper).findHeartbeatStateByCameraCodeForUpdate(CAMERA_CODE);
    }

    @Test
    void retriesNewerReceiptAfterOptimisticConflictUsingLatestLockedState() {
        Instant conflictedHeartbeat = RECEIVED_AT.minusSeconds(1);
        CameraHeartbeatState initial = state("OFFLINE", null);
        CameraHeartbeatState latest = state("ERROR", conflictedHeartbeat);
        when(cameraMapper.findHeartbeatStateByCameraCode(CAMERA_CODE))
                .thenReturn(Optional.of(initial));
        when(cameraMapper.findHeartbeatStateByCameraCodeForUpdate(CAMERA_CODE))
                .thenReturn(Optional.of(latest));
        when(cameraMapper.updateHeartbeat(
                CAMERA_ID, MEDIA_SERVER_ID, "ONLINE", RECEIVED_AT, "OFFLINE", null))
                .thenReturn(0);
        when(cameraMapper.updateHeartbeat(
                CAMERA_ID,
                MEDIA_SERVER_ID,
                "ONLINE",
                RECEIVED_AT,
                "ERROR",
                conflictedHeartbeat))
                .thenReturn(1);

        service.receive(
                PRINCIPAL,
                CAMERA_CODE,
                request(Instant.parse("2026-07-20T02:00:00Z"), "ONLINE", null));

        verify(cameraMapper).updateHeartbeat(
                CAMERA_ID, MEDIA_SERVER_ID, "ONLINE", RECEIVED_AT, "OFFLINE", null);
        verify(cameraMapper).updateHeartbeat(
                CAMERA_ID,
                MEDIA_SERVER_ID,
                "ONLINE",
                RECEIVED_AT,
                "ERROR",
                conflictedHeartbeat);
    }

    @Test
    void classifiesOptimisticUpdateMissAsForeignCamera() {
        when(cameraMapper.findHeartbeatStateByCameraCode(CAMERA_CODE))
                .thenReturn(Optional.of(state("OFFLINE", null)));
        when(cameraMapper.findHeartbeatStateByCameraCodeForUpdate(CAMERA_CODE))
                .thenReturn(Optional.of(new CameraHeartbeatState(
                        CAMERA_ID, 999L, CAMERA_CODE, "ONLINE", RECEIVED_AT)));
        when(cameraMapper.updateHeartbeat(
                CAMERA_ID, MEDIA_SERVER_ID, "ONLINE", RECEIVED_AT, "OFFLINE", null))
                .thenReturn(0);

        assertApiError(
                "ACCESS_DENIED",
                403,
                () -> service.receive(
                        PRINCIPAL,
                        CAMERA_CODE,
                        request(Instant.parse("2026-07-20T02:00:00Z"), "ONLINE", null)));
    }

    @Test
    void classifiesOptimisticUpdateMissAsMissingCamera() {
        when(cameraMapper.findHeartbeatStateByCameraCode(CAMERA_CODE))
                .thenReturn(Optional.of(state("OFFLINE", null)));
        when(cameraMapper.findHeartbeatStateByCameraCodeForUpdate(CAMERA_CODE))
                .thenReturn(Optional.empty());
        when(cameraMapper.updateHeartbeat(
                CAMERA_ID, MEDIA_SERVER_ID, "ONLINE", RECEIVED_AT, "OFFLINE", null))
                .thenReturn(0);

        assertApiError(
                "RESOURCE_NOT_FOUND",
                404,
                () -> service.receive(
                        PRINCIPAL,
                        CAMERA_CODE,
                        request(Instant.parse("2026-07-20T02:00:00Z"), "ONLINE", null)));
    }

    @Test
    void rejectsMalformedDirectServiceStatusAndMissingOccurredAt() {
        assertApiError(
                "VALIDATION_ERROR",
                400,
                () -> service.receive(
                        PRINCIPAL,
                        CAMERA_CODE,
                        new CameraHeartbeatRequest(null, "ONLINE", null)));
        assertApiError(
                "VALIDATION_ERROR",
                400,
                () -> service.receive(
                        PRINCIPAL, CAMERA_CODE, request(Instant.now(), "", null)));
    }

    private CameraHeartbeatState state(String status, Instant lastHeartbeat) {
        return new CameraHeartbeatState(
                CAMERA_ID, MEDIA_SERVER_ID, CAMERA_CODE, status, lastHeartbeat);
    }

    private CameraHeartbeatRequest request(Instant occurredAt, String status, String detail) {
        return new CameraHeartbeatRequest(
                OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC), status, detail);
    }

    private void assertApiError(String code, int status, Runnable action) {
        ApiException exception = assertThrows(ApiException.class, action::run);
        assertEquals(code, exception.getCode());
        assertEquals(status, exception.getStatus().value());
    }
}
