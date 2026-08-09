package com.ssafy.eyesonu.recording.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ssafy.eyesonu.common.exception.ApiException;
import com.ssafy.eyesonu.recording.dto.device.RecordingCreateRequest;
import com.ssafy.eyesonu.recording.dto.device.RecordingUploadUrlCreateRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AdminRecordingRequestValidatorTests {

    private static final String CAMERA_CODE = "camera-01";
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";
    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");

    private final RecordingRequestValidator baseValidator =
            new RecordingRequestValidator(new RecordingObjectKeyFactory());
    private final AdminRecordingRequestValidator validator = new AdminRecordingRequestValidator(
            baseValidator, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsPositiveRangesUpToSixtySecondsThatEndAtOrBeforeNow() {
        OffsetDateTime start = OffsetDateTime.parse("2026-08-08T11:59:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-08-08T12:00:00Z");
        String objectKey = new RecordingObjectKeyFactory().create(CAMERA_CODE, start.toInstant(), KEY);

        assertDoesNotThrow(() -> validator.validateUpload(
                CAMERA_CODE, KEY, new RecordingUploadUrlCreateRequest(start, end)));
        assertDoesNotThrow(() -> validator.validateCreate(
                CAMERA_CODE, KEY, new RecordingCreateRequest(start, end, objectKey)));
    }

    @Test
    void rejectsRangesLongerThanSixtySeconds() {
        ApiException exception = assertThrows(ApiException.class, () -> validator.validateUpload(
                CAMERA_CODE,
                KEY,
                new RecordingUploadUrlCreateRequest(
                        OffsetDateTime.parse("2026-08-08T11:58:59.999999Z"),
                        OffsetDateTime.parse("2026-08-08T12:00:00Z"))));

        assertValidation(exception, "Recording duration must not exceed 60 seconds");
    }

    @Test
    void rejectsRangesEndingInTheFuture() {
        ApiException exception = assertThrows(ApiException.class, () -> validator.validateUpload(
                CAMERA_CODE,
                KEY,
                new RecordingUploadUrlCreateRequest(
                        OffsetDateTime.parse("2026-08-08T11:59:01Z"),
                        OffsetDateTime.parse("2026-08-08T12:00:01Z"))));

        assertValidation(exception, "endTime must not be in the future");
    }

    @Test
    void appliesHistoricalConstraintToCompletionRequests() {
        OffsetDateTime start = OffsetDateTime.parse("2026-08-08T11:59:01Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-08-08T12:00:01Z");
        String objectKey = new RecordingObjectKeyFactory().create(CAMERA_CODE, start.toInstant(), KEY);

        ApiException exception = assertThrows(ApiException.class, () -> validator.validateCreate(
                CAMERA_CODE, KEY, new RecordingCreateRequest(start, end, objectKey)));

        assertValidation(exception, "endTime must not be in the future");
    }

    @Test
    void preservesBaseValidationForPositiveDurationAndServerOwnedObjectKey() {
        OffsetDateTime instant = OffsetDateTime.parse("2026-08-08T11:59:00Z");
        ApiException zeroDuration = assertThrows(ApiException.class, () -> validator.validateUpload(
                CAMERA_CODE, KEY, new RecordingUploadUrlCreateRequest(instant, instant)));
        assertValidation(zeroDuration, "endTime must be after startTime");

        ApiException wrongObjectKey = assertThrows(ApiException.class, () -> validator.validateCreate(
                CAMERA_CODE,
                KEY,
                new RecordingCreateRequest(
                        instant,
                        instant.plusSeconds(30),
                        "recordings/camera-01/wrong.mp4")));
        assertValidation(wrongObjectKey, "objectKey must match the server-issued recording object key");
    }

    @Test
    void deviceValidationStillAllowsFutureAndLongerRecordings() {
        assertDoesNotThrow(() -> baseValidator.validateUpload(
                CAMERA_CODE,
                KEY,
                new RecordingUploadUrlCreateRequest(
                        OffsetDateTime.parse("2026-08-09T00:00:00Z"),
                        OffsetDateTime.parse("2026-08-09T00:05:00Z"))));
    }

    private void assertValidation(ApiException exception, String message) {
        assertEquals(400, exception.getStatus().value());
        assertEquals("VALIDATION_ERROR", exception.getCode());
        assertEquals(message, exception.getMessage());
    }
}
