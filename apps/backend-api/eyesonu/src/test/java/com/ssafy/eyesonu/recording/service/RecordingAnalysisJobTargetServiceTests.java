package com.ssafy.eyesonu.recording.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ssafy.eyesonu.common.config.properties.MinioProperties;
import com.ssafy.eyesonu.common.exception.ApiException;
import com.ssafy.eyesonu.recording.domain.AnalysisJob;
import com.ssafy.eyesonu.recording.domain.RecordingAnalysisPublishSnapshot;
import com.ssafy.eyesonu.recording.mapper.AnalysisJobMapper;
import com.ssafy.eyesonu.storage.StorageObjectUnavailableException;
import com.ssafy.eyesonu.storage.StorageObjectUrlSigner;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecordingAnalysisJobTargetServiceTests {

    private static final long JOB_ID = 42L;
    private static final String WORKER_ID = "recording-ai-worker";
    private static final String RECORDING_OBJECT_KEY = "recordings/CAM-001/video.mp4";
    private static final String RECORDING_DOWNLOAD_URL =
            "https://storage.example.test/eyesonu-media/recordings/CAM-001/video.mp4?signed";
    private static final long RECORDING_FILE_SIZE_BYTES = 20L * 1024 * 1024;

    @Mock private AnalysisJobMapper analysisJobMapper;
    @Mock private StorageObjectUrlSigner urlSigner;

    private RecordingAnalysisJobTargetService service;

    @BeforeEach
    void setUp() {
        MinioProperties properties = new MinioProperties();
        properties.setPresignedUrlExpiry(Duration.ofMinutes(15));
        service = new RecordingAnalysisJobTargetService(
                analysisJobMapper, new RecordingAnalysisTimeRangeCalculator(), urlSigner, properties);
    }

    @Test
    void returnsPromptAndRecordingTargetForClaimedWorker() {
        AnalysisJob job = runningJob();
        RecordingAnalysisPublishSnapshot snapshot = snapshot();
        when(analysisJobMapper.findRecordingAnalysisById(JOB_ID)).thenReturn(job);
        when(analysisJobMapper.findRecordingAnalysisPublishSnapshot(JOB_ID, 101L)).thenReturn(snapshot);
        when(urlSigner.createGetUrl(RECORDING_OBJECT_KEY)).thenReturn(RECORDING_DOWNLOAD_URL);

        var response = service.find(JOB_ID, WORKER_ID);

        assertEquals(JOB_ID, response.jobId());
        assertEquals(RECORDING_OBJECT_KEY, response.recordingObjectKey());
        assertEquals(RECORDING_DOWNLOAD_URL, response.recordingDownloadUrl());
        assertEquals(900, response.recordingDownloadUrlExpiresInSeconds());
        assertEquals(RECORDING_FILE_SIZE_BYTES, response.recordingFileSizeBytes());
        assertEquals("video/mp4", response.recordingContentType());
        assertEquals("a person wearing a black short sleeve top and blue pants", response.prompt());
        assertEquals("a person wearing a red long sleeve top and black pants", response.exclusionPrompt());
        assertEquals(Instant.parse("2026-08-02T23:45:00Z"), response.recordingStart());
        assertEquals(Instant.parse("2026-08-03T00:20:00Z"), response.recordingEnd());
        assertEquals(Instant.parse("2026-08-03T00:00:00Z"), response.analysisStart());
        assertEquals(Instant.parse("2026-08-03T00:20:00Z"), response.analysisEnd());
        assertEquals(1, response.attempt());
        verify(urlSigner).createGetUrl(RECORDING_OBJECT_KEY);
    }

    @Test
    void rejectsUnauthenticatedWorkerWithoutSigning() {
        ApiException exception = assertThrows(ApiException.class, () -> service.find(JOB_ID, " "));

        assertEquals(401, exception.getStatus().value());
        assertEquals("AUTHENTICATION_REQUIRED", exception.getCode());
        verifyNoInteractions(analysisJobMapper, urlSigner);
    }

    @Test
    void rejectsMissingJobWithoutSigning() {
        when(analysisJobMapper.findRecordingAnalysisById(JOB_ID)).thenReturn(null);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.find(JOB_ID, WORKER_ID));

        assertEquals(404, exception.getStatus().value());
        assertEquals("RESOURCE_NOT_FOUND", exception.getCode());
        verifyNoInteractions(urlSigner);
    }

    @Test
    void rejectsNonRunningJobWithoutSigning() {
        AnalysisJob job = runningJob();
        job.setStatus("QUEUED");
        when(analysisJobMapper.findRecordingAnalysisById(JOB_ID)).thenReturn(job);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.find(JOB_ID, WORKER_ID));

        assertEquals(409, exception.getStatus().value());
        assertEquals("JOB_NOT_RUNNABLE", exception.getCode());
        verify(analysisJobMapper, never()).findRecordingAnalysisPublishSnapshot(eq(JOB_ID), eq(101L));
        verifyNoInteractions(urlSigner);
    }

    @Test
    void rejectsWorkerThatDoesNotOwnTheJobLease() {
        when(analysisJobMapper.findRecordingAnalysisById(JOB_ID)).thenReturn(runningJob());

        ApiException exception = assertThrows(ApiException.class, () ->
                service.find(JOB_ID, "another-worker"));

        assertEquals("JOB_NOT_CLAIMED", exception.getCode());
        verify(analysisJobMapper, never()).findRecordingAnalysisPublishSnapshot(eq(JOB_ID), eq(101L));
        verifyNoInteractions(urlSigner);
    }

    @Test
    void rejectsMissingTargetSnapshotWithoutSigning() {
        when(analysisJobMapper.findRecordingAnalysisById(JOB_ID)).thenReturn(runningJob());
        when(analysisJobMapper.findRecordingAnalysisPublishSnapshot(JOB_ID, 101L)).thenReturn(null);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.find(JOB_ID, WORKER_ID));

        assertEquals(422, exception.getStatus().value());
        assertEquals("BUSINESS_RULE_VIOLATION", exception.getCode());
        verifyNoInteractions(urlSigner);
    }

    @Test
    void rejectsLegacyTargetWithoutAValidIntersection() {
        RecordingAnalysisPublishSnapshot snapshot = snapshot();
        snapshot.setSearchStart(null);
        snapshot.setSearchEnd(null);
        when(analysisJobMapper.findRecordingAnalysisById(JOB_ID)).thenReturn(runningJob());
        when(analysisJobMapper.findRecordingAnalysisPublishSnapshot(JOB_ID, 101L)).thenReturn(snapshot);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.find(JOB_ID, WORKER_ID));

        assertEquals("BUSINESS_RULE_VIOLATION", exception.getCode());
        verifyNoInteractions(urlSigner);
    }

    @Test
    void mapsSigningFailureToStorageUnavailable() {
        when(analysisJobMapper.findRecordingAnalysisById(JOB_ID)).thenReturn(runningJob());
        when(analysisJobMapper.findRecordingAnalysisPublishSnapshot(JOB_ID, 101L))
                .thenReturn(snapshot());
        when(urlSigner.createGetUrl(RECORDING_OBJECT_KEY))
                .thenThrow(new StorageObjectUnavailableException(new RuntimeException("down")));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.find(JOB_ID, WORKER_ID));

        assertEquals(503, exception.getStatus().value());
        assertEquals("STORAGE_UNAVAILABLE", exception.getCode());
        verify(urlSigner).createGetUrl(RECORDING_OBJECT_KEY);
    }

    private AnalysisJob runningJob() {
        AnalysisJob job = new AnalysisJob();
        job.setId(JOB_ID);
        job.setCaseId(101L);
        job.setStatus("RUNNING");
        job.setClaimedBy(WORKER_ID);
        return job;
    }

    private RecordingAnalysisPublishSnapshot snapshot() {
        RecordingAnalysisPublishSnapshot snapshot = new RecordingAnalysisPublishSnapshot();
        snapshot.setJobId(JOB_ID);
        snapshot.setCaseId(101L);
        snapshot.setRecordingId(3001L);
        snapshot.setCameraId(11L);
        snapshot.setCameraCode("CAM-001");
        snapshot.setCameraName("Front");
        snapshot.setRecordingObjectKey(RECORDING_OBJECT_KEY);
        snapshot.setRecordingFileSizeBytes(RECORDING_FILE_SIZE_BYTES);
        snapshot.setRecordingStart(Instant.parse("2026-08-02T23:45:00Z"));
        snapshot.setRecordingEnd(Instant.parse("2026-08-03T00:20:00Z"));
        snapshot.setPrompt("a person wearing a black short sleeve top and blue pants");
        snapshot.setExclusionPrompt("a person wearing a red long sleeve top and black pants");
        snapshot.setSearchStart(Instant.parse("2026-08-03T00:00:00Z"));
        snapshot.setSearchEnd(Instant.parse("2026-08-03T00:30:00Z"));
        snapshot.setSearchArea("front gate");
        snapshot.setAttempt(1);
        return snapshot;
    }
}
