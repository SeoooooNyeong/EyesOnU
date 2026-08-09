package com.ssafy.eyesonu.recording.service;

import com.ssafy.eyesonu.common.config.properties.MinioProperties;
import com.ssafy.eyesonu.common.exception.ApiException;
import com.ssafy.eyesonu.recording.domain.AnalysisJob;
import com.ssafy.eyesonu.recording.dto.device.RecordingAnalysisJobTargetResponse;
import com.ssafy.eyesonu.recording.mapper.AnalysisJobMapper;
import com.ssafy.eyesonu.storage.StorageObjectUnavailableException;
import com.ssafy.eyesonu.storage.StorageObjectUrlSigner;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RecordingAnalysisJobTargetService {

    private static final String RECORDING_CONTENT_TYPE = "video/mp4";

    private final AnalysisJobMapper analysisJobMapper;
    private final RecordingAnalysisTimeRangeCalculator timeRangeCalculator;
    private final StorageObjectUrlSigner urlSigner;
    private final MinioProperties minioProperties;

    public RecordingAnalysisJobTargetService(
            AnalysisJobMapper analysisJobMapper,
            RecordingAnalysisTimeRangeCalculator timeRangeCalculator,
            StorageObjectUrlSigner urlSigner,
            MinioProperties minioProperties) {
        this.analysisJobMapper = analysisJobMapper;
        this.timeRangeCalculator = timeRangeCalculator;
        this.urlSigner = urlSigner;
        this.minioProperties = minioProperties;
    }

    public RecordingAnalysisJobTargetResponse find(Long jobId, String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED", "Authenticated worker is required.");
        }

        AnalysisJob job = analysisJobMapper.findRecordingAnalysisById(jobId);
        if (job == null) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "RESOURCE_NOT_FOUND", "Recording analysis job was not found.");
        }
        if (!"RUNNING".equals(job.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "JOB_NOT_RUNNABLE", "Only running recording analysis jobs can read the target.");
        }
        if (!Objects.equals(job.getClaimedBy(), workerId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "JOB_NOT_CLAIMED", "Recording analysis job is claimed by another worker.");
        }

        var snapshot = analysisJobMapper.findRecordingAnalysisPublishSnapshot(jobId, job.getCaseId());
        if (snapshot == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "BUSINESS_RULE_VIOLATION", "Recording analysis target could not be loaded.");
        }
        var analysisRange = timeRangeCalculator.requireIntersection(
                snapshot.getSearchStart(), snapshot.getSearchEnd(),
                snapshot.getRecordingStart(), snapshot.getRecordingEnd());
        String recordingDownloadUrl;
        try {
            recordingDownloadUrl = urlSigner.createGetUrl(snapshot.getRecordingObjectKey());
        } catch (StorageObjectUnavailableException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "STORAGE_UNAVAILABLE", "Recording download URL could not be created.");
        }
        return RecordingAnalysisJobTargetResponse.from(
                snapshot, recordingDownloadUrl,
                minioProperties.getPresignedUrlExpiry().toSeconds(), RECORDING_CONTENT_TYPE,
                analysisRange.start(), analysisRange.end());
    }
}
