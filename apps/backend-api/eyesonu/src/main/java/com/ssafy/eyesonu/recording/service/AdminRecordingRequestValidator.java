package com.ssafy.eyesonu.recording.service;

import com.ssafy.eyesonu.common.exception.ApiException;
import com.ssafy.eyesonu.recording.dto.device.RecordingCreateRequest;
import com.ssafy.eyesonu.recording.dto.device.RecordingUploadUrlCreateRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AdminRecordingRequestValidator {

    private static final Duration MAX_RECORDING_DURATION = Duration.ofSeconds(60);

    private final RecordingRequestValidator recordingRequestValidator;
    private final Clock clock;

    public AdminRecordingRequestValidator(
            RecordingRequestValidator recordingRequestValidator,
            Clock clock) {
        this.recordingRequestValidator = recordingRequestValidator;
        this.clock = clock;
    }

    public void validateUpload(
            String cameraCode,
            String idempotencyKey,
            RecordingUploadUrlCreateRequest request) {
        NormalizedRecordingUploadRequest normalized = recordingRequestValidator.validateUpload(
                cameraCode, idempotencyKey, request);
        validateHistoricalRange(normalized.startTime(), normalized.endTime());
    }

    public void validateCreate(
            String cameraCode,
            String idempotencyKey,
            RecordingCreateRequest request) {
        NormalizedRecordingCreateRequest normalized = recordingRequestValidator.validate(
                cameraCode, idempotencyKey, request);
        validateHistoricalRange(normalized.startTime(), normalized.endTime());
    }

    private void validateHistoricalRange(Instant startTime, Instant endTime) {
        if (Duration.between(startTime, endTime).compareTo(MAX_RECORDING_DURATION) > 0) {
            throw validationError("Recording duration must not exceed 60 seconds");
        }
        if (endTime.isAfter(clock.instant())) {
            throw validationError("endTime must not be in the future");
        }
    }

    private ApiException validationError(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}
