package com.ssafy.eyesonu.recording.dto.device;

import com.ssafy.eyesonu.recording.domain.RecordingAnalysisPublishSnapshot;
import java.time.Instant;

public record RecordingAnalysisJobTargetResponse(
        Long jobId,
        Long caseId,
        Long recordingId,
        Long cameraId,
        String cameraCode,
        String cameraName,
        String recordingObjectKey,
        String recordingDownloadUrl,
        long recordingDownloadUrlExpiresInSeconds,
        long recordingFileSizeBytes,
        String recordingContentType,
        Instant recordingStart,
        Instant recordingEnd,
        String prompt,
        String exclusionPrompt,
        Instant searchStart,
        Instant searchEnd,
        Instant analysisStart,
        Instant analysisEnd,
        String searchArea,
        int attempt) {

    public static RecordingAnalysisJobTargetResponse from(
            RecordingAnalysisPublishSnapshot snapshot,
            String recordingDownloadUrl,
            long recordingDownloadUrlExpiresInSeconds,
            String recordingContentType,
            Instant analysisStart,
            Instant analysisEnd) {
        return new RecordingAnalysisJobTargetResponse(
                snapshot.getJobId(), snapshot.getCaseId(), snapshot.getRecordingId(), snapshot.getCameraId(),
                snapshot.getCameraCode(), snapshot.getCameraName(), snapshot.getRecordingObjectKey(),
                recordingDownloadUrl, recordingDownloadUrlExpiresInSeconds,
                snapshot.getRecordingFileSizeBytes(), recordingContentType,
                snapshot.getRecordingStart(), snapshot.getRecordingEnd(),
                snapshot.getPrompt(), snapshot.getExclusionPrompt(), snapshot.getSearchStart(),
                snapshot.getSearchEnd(), analysisStart, analysisEnd,
                snapshot.getSearchArea(), snapshot.getAttempt());
    }
}
