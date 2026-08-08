package com.ssafy.eyesonu.recording.service;

import com.ssafy.eyesonu.audit.service.AuditService;
import com.ssafy.eyesonu.auth.device.MediaServerPrincipal;
import com.ssafy.eyesonu.auth.security.AdminPrincipal;
import com.ssafy.eyesonu.camera.domain.Camera;
import com.ssafy.eyesonu.camera.mapper.CameraMapper;
import com.ssafy.eyesonu.common.exception.ApiException;
import com.ssafy.eyesonu.recording.dto.device.RecordingCreateRequest;
import com.ssafy.eyesonu.recording.dto.device.RecordingUploadUrlCreateRequest;
import com.ssafy.eyesonu.recording.dto.device.RecordingUploadUrlCreateResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AdminRecordingCommandService {

    private static final String ADMIN_MEDIA_SERVER_CODE = "admin-manual-recording";

    private final CameraMapper cameraMapper;
    private final AdminRecordingRequestValidator requestValidator;
    private final RecordingUploadUrlService uploadUrlService;
    private final RecordingCommandService commandService;
    private final AuditService auditService;

    public AdminRecordingCommandService(
            CameraMapper cameraMapper,
            AdminRecordingRequestValidator requestValidator,
            RecordingUploadUrlService uploadUrlService,
            RecordingCommandService commandService,
            AuditService auditService) {
        this.cameraMapper = cameraMapper;
        this.requestValidator = requestValidator;
        this.uploadUrlService = uploadUrlService;
        this.commandService = commandService;
        this.auditService = auditService;
    }

    public RecordingUploadUrlCreateResponse createUploadUrl(
            AdminPrincipal principal,
            String cameraCode,
            String idempotencyKey,
            RecordingUploadUrlCreateRequest request) {
        requireAdmin(principal);
        requestValidator.validateUpload(cameraCode, idempotencyKey, request);
        MediaServerPrincipal mediaServerPrincipal = resolveMediaServerPrincipal(cameraCode);
        return uploadUrlService.create(mediaServerPrincipal, cameraCode, idempotencyKey, request);
    }

    public RecordingCreateResult create(
            AdminPrincipal principal,
            String cameraCode,
            String idempotencyKey,
            RecordingCreateRequest request) {
        requireAdmin(principal);
        requestValidator.validateCreate(cameraCode, idempotencyKey, request);
        MediaServerPrincipal mediaServerPrincipal = resolveMediaServerPrincipal(cameraCode);
        RecordingCreateResult result = commandService.create(
                mediaServerPrincipal, cameraCode, idempotencyKey, request);
        if (!result.duplicate()) {
            auditService.recordBestEffort(
                    "MANUAL_RECORDING_CREATED",
                    principal.getAdminId(),
                    null,
                    "RECORDING",
                    result.recording().getId(),
                    Map.of(
                            "cameraId", result.recording().getCameraId(),
                            "cameraCode", cameraCode,
                            "startTime", result.recording().getStartTime().toString(),
                            "endTime", result.recording().getEndTime().toString(),
                            "fileSize", result.recording().getFileSize()));
        }
        return result;
    }

    private MediaServerPrincipal resolveMediaServerPrincipal(String cameraCode) {
        Camera camera = cameraMapper.findByCameraCode(cameraCode)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Camera was not found"));
        return new MediaServerPrincipal(camera.mediaServerId(), ADMIN_MEDIA_SERVER_CODE);
    }

    private void requireAdmin(AdminPrincipal principal) {
        if (principal == null || principal.getAdminId() == null) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required");
        }
    }
}
