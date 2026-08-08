package com.ssafy.eyesonu.recording.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ssafy.eyesonu.admin.domain.AdminRole;
import com.ssafy.eyesonu.audit.service.AuditService;
import com.ssafy.eyesonu.auth.device.MediaServerPrincipal;
import com.ssafy.eyesonu.auth.security.AdminPrincipal;
import com.ssafy.eyesonu.camera.domain.Camera;
import com.ssafy.eyesonu.camera.mapper.CameraMapper;
import com.ssafy.eyesonu.common.exception.ApiException;
import com.ssafy.eyesonu.recording.domain.Recording;
import com.ssafy.eyesonu.recording.dto.device.RecordingCreateRequest;
import com.ssafy.eyesonu.recording.dto.device.RecordingUploadUrlCreateRequest;
import com.ssafy.eyesonu.recording.dto.device.RecordingUploadUrlCreateResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminRecordingCommandServiceTests {

    private static final String CAMERA_CODE = "camera-01";
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";
    private static final String OBJECT_KEY = "recordings/camera-01/video.mp4";
    private static final AdminPrincipal ADMIN = new AdminPrincipal(3L, "admin", AdminRole.ADMIN);
    private static final MediaServerPrincipal MEDIA_SERVER =
            new MediaServerPrincipal(7L, "admin-manual-recording");

    private final CameraMapper cameraMapper = mock(CameraMapper.class);
    private final AdminRecordingRequestValidator requestValidator =
            mock(AdminRecordingRequestValidator.class);
    private final RecordingUploadUrlService uploadUrlService = mock(RecordingUploadUrlService.class);
    private final RecordingCommandService recordingCommandService = mock(RecordingCommandService.class);
    private final AuditService auditService = mock(AuditService.class);

    private AdminRecordingCommandService service;

    @BeforeEach
    void setUp() {
        service = new AdminRecordingCommandService(
                cameraMapper, requestValidator, uploadUrlService, recordingCommandService, auditService);
        when(cameraMapper.findByCameraCode(CAMERA_CODE)).thenReturn(Optional.of(
                new Camera(11L, 7L, CAMERA_CODE, "Camera")));
    }

    @Test
    void uploadUrlUsesSelectedCamerasActualMediaServerScope() {
        RecordingUploadUrlCreateRequest request = uploadRequest();
        RecordingUploadUrlCreateResponse expected = new RecordingUploadUrlCreateResponse(
                OBJECT_KEY, "https://storage.example/upload", "video/mp4", 900L, 104_857_600L);
        when(uploadUrlService.create(MEDIA_SERVER, CAMERA_CODE, KEY, request)).thenReturn(expected);

        RecordingUploadUrlCreateResponse result = service.createUploadUrl(
                ADMIN, CAMERA_CODE, KEY, request);

        assertSame(expected, result);
        verify(requestValidator).validateUpload(CAMERA_CODE, KEY, request);
        verify(uploadUrlService).create(MEDIA_SERVER, CAMERA_CODE, KEY, request);
    }

    @Test
    void missingCameraFailsBeforeDelegatingToDeviceFlow() {
        when(cameraMapper.findByCameraCode("missing")).thenReturn(Optional.empty());
        RecordingUploadUrlCreateRequest request = uploadRequest();

        ApiException exception = assertThrows(ApiException.class, () -> service.createUploadUrl(
                ADMIN, "missing", KEY, request));

        assertEquals(404, exception.getStatus().value());
        assertEquals("RESOURCE_NOT_FOUND", exception.getCode());
        verify(requestValidator).validateUpload("missing", KEY, request);
        verifyNoInteractions(uploadUrlService);
    }

    @Test
    void firstCreationRecordsBestEffortManualAuditWithAdminActor() {
        RecordingCreateRequest request = createRequest();
        Recording recording = recording();
        RecordingCreateResult expected = new RecordingCreateResult(recording, false);
        when(recordingCommandService.create(MEDIA_SERVER, CAMERA_CODE, KEY, request)).thenReturn(expected);

        RecordingCreateResult result = service.create(ADMIN, CAMERA_CODE, KEY, request);

        assertSame(expected, result);
        verify(requestValidator).validateCreate(CAMERA_CODE, KEY, request);
        verify(recordingCommandService).create(MEDIA_SERVER, CAMERA_CODE, KEY, request);
        verify(auditService).recordBestEffort(
                eq("MANUAL_RECORDING_CREATED"),
                eq(ADMIN.getAdminId()),
                isNull(),
                eq("RECORDING"),
                eq(recording.getId()),
                argThat(detail -> auditDetailMatches(detail, recording)));
    }

    @Test
    void idempotentReplayDoesNotWriteAnotherAuditEvent() {
        RecordingCreateRequest request = createRequest();
        RecordingCreateResult expected = new RecordingCreateResult(recording(), true);
        when(recordingCommandService.create(MEDIA_SERVER, CAMERA_CODE, KEY, request)).thenReturn(expected);

        RecordingCreateResult result = service.create(ADMIN, CAMERA_CODE, KEY, request);

        assertSame(expected, result);
        verify(auditService, never()).recordBestEffort(
                eq("MANUAL_RECORDING_CREATED"),
                eq(ADMIN.getAdminId()),
                isNull(),
                eq("RECORDING"),
                eq(result.recording().getId()),
                org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void missingAdminPrincipalIsRejectedBeforeValidationOrStorage() {
        ApiException exception = assertThrows(ApiException.class, () -> service.createUploadUrl(
                null, CAMERA_CODE, KEY, uploadRequest()));

        assertEquals(401, exception.getStatus().value());
        assertEquals("AUTHENTICATION_REQUIRED", exception.getCode());
        verifyNoInteractions(requestValidator, uploadUrlService, recordingCommandService, auditService);
    }

    private boolean auditDetailMatches(Map<String, ?> detail, Recording recording) {
        return detail.equals(Map.of(
                "cameraId", recording.getCameraId(),
                "cameraCode", CAMERA_CODE,
                "startTime", recording.getStartTime().toString(),
                "endTime", recording.getEndTime().toString(),
                "fileSize", recording.getFileSize()));
    }

    private RecordingUploadUrlCreateRequest uploadRequest() {
        return new RecordingUploadUrlCreateRequest(
                OffsetDateTime.parse("2026-08-08T01:00:00Z"),
                OffsetDateTime.parse("2026-08-08T01:01:00Z"));
    }

    private RecordingCreateRequest createRequest() {
        return new RecordingCreateRequest(
                OffsetDateTime.parse("2026-08-08T01:00:00Z"),
                OffsetDateTime.parse("2026-08-08T01:01:00Z"),
                OBJECT_KEY);
    }

    private Recording recording() {
        return new Recording(
                501L,
                11L,
                Instant.parse("2026-08-08T01:00:00Z"),
                Instant.parse("2026-08-08T01:01:00Z"),
                OBJECT_KEY,
                1024L,
                Instant.parse("2026-08-08T02:00:00Z"));
    }
}
