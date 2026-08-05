package com.ssafy.eyesonu.recording.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ssafy.eyesonu.common.exception.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RecordingAnalysisTimeRangeCalculatorTests {

    private final RecordingAnalysisTimeRangeCalculator calculator =
            new RecordingAnalysisTimeRangeCalculator();

    @Test
    void calculatesIntersectionForEveryPositiveOverlapShape() {
        assertRange(
                "2026-08-05T05:00:00Z", "2026-08-05T07:00:00Z",
                "2026-08-05T05:00:00Z", "2026-08-05T07:00:00Z",
                "2026-08-05T04:30:00Z", "2026-08-05T08:00:00Z");
        assertRange(
                "2026-08-05T05:00:00Z", "2026-08-05T06:00:00Z",
                "2026-08-05T05:00:00Z", "2026-08-05T07:00:00Z",
                "2026-08-05T04:30:00Z", "2026-08-05T06:00:00Z");
        assertRange(
                "2026-08-05T05:30:00Z", "2026-08-05T06:30:00Z",
                "2026-08-05T05:00:00Z", "2026-08-05T07:00:00Z",
                "2026-08-05T05:30:00Z", "2026-08-05T06:30:00Z");
        assertRange(
                "2026-08-05T05:30:00Z", "2026-08-05T07:00:00Z",
                "2026-08-05T05:00:00Z", "2026-08-05T07:00:00Z",
                "2026-08-05T05:30:00Z", "2026-08-05T08:00:00Z");
    }

    @Test
    void rejectsMissingReversedAndZeroLengthSearchRanges() {
        assertBusinessRule(null, null, "2026-08-05T04:00:00Z", "2026-08-05T05:00:00Z");
        assertBusinessRule("2026-08-05T05:00:00Z", "2026-08-05T04:00:00Z",
                "2026-08-05T04:00:00Z", "2026-08-05T05:00:00Z");
        assertBusinessRule("2026-08-05T05:00:00Z", "2026-08-05T05:00:00Z",
                "2026-08-05T04:00:00Z", "2026-08-05T06:00:00Z");
    }

    @Test
    void rejectsDisjointAndBoundaryTouchingRanges() {
        assertBusinessRule("2026-08-05T05:00:00Z", "2026-08-05T06:00:00Z",
                "2026-08-05T04:00:00Z", "2026-08-05T05:00:00Z");
        assertBusinessRule("2026-08-05T05:00:00Z", "2026-08-05T06:00:00Z",
                "2026-08-05T06:00:00Z", "2026-08-05T07:00:00Z");
        assertBusinessRule("2026-08-05T05:00:00Z", "2026-08-05T06:00:00Z",
                "2026-08-05T07:00:00Z", "2026-08-05T08:00:00Z");
    }

    private void assertRange(
            String expectedStart, String expectedEnd,
            String searchStart, String searchEnd,
            String recordingStart, String recordingEnd) {
        var range = calculator.requireIntersection(
                Instant.parse(searchStart), Instant.parse(searchEnd),
                Instant.parse(recordingStart), Instant.parse(recordingEnd));
        assertEquals(Instant.parse(expectedStart), range.start());
        assertEquals(Instant.parse(expectedEnd), range.end());
    }

    private void assertBusinessRule(
            String searchStart, String searchEnd, String recordingStart, String recordingEnd) {
        ApiException exception = assertThrows(ApiException.class, () -> calculator.requireIntersection(
                instant(searchStart), instant(searchEnd), instant(recordingStart), instant(recordingEnd)));
        assertEquals("BUSINESS_RULE_VIOLATION", exception.getCode());
    }

    private Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
