package com.ssafy.eyesonu.recording.service;

import com.ssafy.eyesonu.common.exception.ApiException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class RecordingAnalysisTimeRangeCalculator {

    public AnalysisRange requireIntersection(
            Instant searchStart,
            Instant searchEnd,
            Instant recordingStart,
            Instant recordingEnd) {
        if (searchStart == null || searchEnd == null || !searchStart.isBefore(searchEnd)) {
            throw businessRule("Recording analysis requires a valid searchStart and searchEnd.");
        }
        if (recordingStart == null || recordingEnd == null || !recordingStart.isBefore(recordingEnd)) {
            throw businessRule("Recording time range is invalid.");
        }

        Instant analysisStart = searchStart.isAfter(recordingStart) ? searchStart : recordingStart;
        Instant analysisEnd = searchEnd.isBefore(recordingEnd) ? searchEnd : recordingEnd;
        if (!analysisStart.isBefore(analysisEnd)) {
            throw businessRule("Recording does not overlap the search condition time range.");
        }
        return new AnalysisRange(analysisStart, analysisEnd);
    }

    private ApiException businessRule(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION", message);
    }

    public record AnalysisRange(Instant start, Instant end) {
    }
}
