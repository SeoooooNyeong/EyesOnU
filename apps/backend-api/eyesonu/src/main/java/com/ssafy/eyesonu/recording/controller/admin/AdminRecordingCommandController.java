package com.ssafy.eyesonu.recording.controller.admin;

import com.ssafy.eyesonu.auth.security.AdminPrincipal;
import com.ssafy.eyesonu.common.api.ApiResponse;
import com.ssafy.eyesonu.recording.dto.device.RecordingCreateRequest;
import com.ssafy.eyesonu.recording.dto.device.RecordingCreateResponse;
import com.ssafy.eyesonu.recording.dto.device.RecordingUploadUrlCreateRequest;
import com.ssafy.eyesonu.recording.dto.device.RecordingUploadUrlCreateResponse;
import com.ssafy.eyesonu.recording.service.AdminRecordingCommandService;
import com.ssafy.eyesonu.recording.service.RecordingCreateResult;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/cameras/{cameraCode}")
public class AdminRecordingCommandController {

    private final AdminRecordingCommandService commandService;

    public AdminRecordingCommandController(AdminRecordingCommandService commandService) {
        this.commandService = commandService;
    }

    @PostMapping(value = "/recording-upload-urls", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<RecordingUploadUrlCreateResponse>> createUploadUrl(
            @PathVariable String cameraCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal AdminPrincipal principal,
            @Valid @RequestBody RecordingUploadUrlCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(commandService.createUploadUrl(
                        principal, cameraCode, idempotencyKey, request)));
    }

    @PostMapping(value = "/recordings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<RecordingCreateResponse>> create(
            @PathVariable String cameraCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal AdminPrincipal principal,
            @Valid @RequestBody RecordingCreateRequest request) {
        RecordingCreateResult result = commandService.create(
                principal, cameraCode, idempotencyKey, request);
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiResponse.of(
                RecordingCreateResponse.from(result.recording(), result.duplicate())));
    }
}
