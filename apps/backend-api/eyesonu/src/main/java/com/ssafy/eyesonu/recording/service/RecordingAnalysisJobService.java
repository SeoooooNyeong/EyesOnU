package com.ssafy.eyesonu.recording.service;

import com.ssafy.eyesonu.audit.service.AuditService;
import com.ssafy.eyesonu.common.exception.ApiException;
import com.ssafy.eyesonu.common.logging.LogExecution;
import com.ssafy.eyesonu.missingcase.domain.CaseStatus;
import com.ssafy.eyesonu.missingcase.domain.MissingCaseRow;
import com.ssafy.eyesonu.missingcase.domain.SearchConditionRow;
import com.ssafy.eyesonu.missingcase.mapper.MissingCaseMapper;
import com.ssafy.eyesonu.missingcase.service.CaseQueryService;
import com.ssafy.eyesonu.recording.domain.AnalysisJob;
import com.ssafy.eyesonu.recording.domain.Recording;
import com.ssafy.eyesonu.recording.dto.admin.RecordingAnalysisJobCreateRequest;
import com.ssafy.eyesonu.recording.dto.admin.RecordingAnalysisJobResponse;
import com.ssafy.eyesonu.recording.mapper.AnalysisJobMapper;
import com.ssafy.eyesonu.recording.mapper.RecordingMapper;
import com.ssafy.eyesonu.recording.messaging.RecordingAnalysisJobPublisher;
import com.ssafy.eyesonu.storage.StorageObject;
import com.ssafy.eyesonu.storage.StorageObjectNotFoundException;
import com.ssafy.eyesonu.storage.StorageObjectUnavailableException;
import com.ssafy.eyesonu.storage.StorageObjectVerifier;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecordingAnalysisJobService {

    private static final String RECORDING_ANALYSIS = "RECORDING_ANALYSIS";
    private static final String QUEUED = "QUEUED";
    private static final Logger log = LoggerFactory.getLogger(RecordingAnalysisJobService.class);

    private final AnalysisJobMapper analysisJobMapper;
    private final MissingCaseMapper missingCaseMapper;
    private final CaseQueryService caseQueryService;
    private final RecordingMapper recordingMapper;
    private final StorageObjectVerifier storageObjectVerifier;
    private final AuditService auditService;
    private final RecordingAnalysisJobPublisher recordingAnalysisJobPublisher;
    private final RecordingAnalysisTimeRangeCalculator timeRangeCalculator;

    public RecordingAnalysisJobService(
            AnalysisJobMapper analysisJobMapper,
            MissingCaseMapper missingCaseMapper,
            CaseQueryService caseQueryService,
            RecordingMapper recordingMapper,
            StorageObjectVerifier storageObjectVerifier,
            AuditService auditService,
            RecordingAnalysisJobPublisher recordingAnalysisJobPublisher,
            RecordingAnalysisTimeRangeCalculator timeRangeCalculator) {
        this.analysisJobMapper = analysisJobMapper;
        this.missingCaseMapper = missingCaseMapper;
        this.caseQueryService = caseQueryService;
        this.recordingMapper = recordingMapper;
        this.storageObjectVerifier = storageObjectVerifier;
        this.auditService = auditService;
        this.recordingAnalysisJobPublisher = recordingAnalysisJobPublisher;
        this.timeRangeCalculator = timeRangeCalculator;
    }

    @Transactional
    @LogExecution
    public RecordingAnalysisJobResponse create(
            Long caseId, RecordingAnalysisJobCreateRequest request, Long adminId) {
        MissingCaseRow missingCase = caseQueryService.require(caseId);
        if (missingCase.getStatus() == CaseStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_STATE_CONFLICT",
                    "A closed case cannot register a recording analysis job.");
        }

        SearchConditionRow condition = missingCaseMapper.findSearchCondition(caseId, request.conditionId());
        if (condition == null) {
            throw notFound("Search condition was not found.");
        }

        Recording recording = recordingMapper.findById(request.recordingId());
        if (recording == null) {
            throw notFound("Recording was not found.");
        }
        timeRangeCalculator.requireIntersection(
                condition.getSearchStart(), condition.getSearchEnd(),
                recording.getStartTime(), recording.getEndTime());

        AnalysisJob existing = analysisJobMapper.findActiveByTarget(
                caseId, condition.getId(), recording.getId());
        if (existing != null) {
            throw duplicateJob();
        }

        verifyRecordingObject(recording.getS3Key());
        if (!missingCaseMapper.existsActiveCaseCamera(caseId, recording.getCameraId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION",
                    "The recording camera is not enabled for this case.");
        }

        AnalysisJob job = new AnalysisJob();
        job.setCaseId(caseId);
        job.setSearchConditionId(condition.getId());
        job.setRecordingId(recording.getId());
        job.setJobType(RECORDING_ANALYSIS);
        job.setStatus(QUEUED);
        job.setPromptSnapshot(condition.getPrompt());
        job.setExclusionPromptSnapshot(condition.getExclusionPrompt());
        job.setSearchStartSnapshot(condition.getSearchStart());
        job.setSearchEndSnapshot(condition.getSearchEnd());
        job.setSearchAreaSnapshot(condition.getSearchArea());
        try {
            analysisJobMapper.insert(job);
        } catch (DuplicateKeyException exception) {
            throw duplicateJob();
        }

        auditService.recordRequired("RECORDING_ANALYSIS_JOB_CREATED", adminId, caseId,
                "ANALYSIS_JOB", job.getId(),
                Map.of("conditionId", condition.getId(), "recordingId", recording.getId()));
        recordingAnalysisJobPublisher.enqueue(job.getId(), caseId);
        log.info(
                "business_event=analysis_job_created caseId={} recordingId={} jobId={} status={}",
                caseId,
                recording.getId(),
                job.getId(),
                job.getStatus());
        return RecordingAnalysisJobResponse.from(job);
    }

    public RecordingAnalysisJobResponse findById(Long caseId, Long jobId) {
        AnalysisJob job = analysisJobMapper.findById(caseId, jobId);
        if (job == null) {
            throw notFound("Recording analysis job was not found.");
        }
        return RecordingAnalysisJobResponse.from(job);
    }

    @Transactional(readOnly = true)
    public List<RecordingAnalysisJobResponse> findAll(Long caseId) {
        caseQueryService.require(caseId);
        List<RecordingAnalysisJobResponse> jobs = analysisJobMapper.findRecordingAnalysisByCaseId(caseId).stream()
                .map(RecordingAnalysisJobResponse::from)
                .toList();
        log.debug("analysis_job_list_queried caseId={} resultCount={}", caseId, jobs.size());
        return jobs;
    }

    @Transactional(readOnly = true)
    public List<RecordingAnalysisJobResponse> findAllForDashboard(List<Long> caseIds) {
        return analysisJobMapper.findRecordingAnalysisByCaseIds(caseIds).stream()
                .map(RecordingAnalysisJobResponse::from)
                .toList();
    }

    @Transactional
    public RecordingAnalysisJobResponse cancel(Long caseId, Long jobId, Long adminId) {
        AnalysisJob job = requireJob(caseId, jobId);
        if (analysisJobMapper.cancelActive(caseId, jobId) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_STATE_CONFLICT",
                    "Only queued or running recording analysis jobs can be cancelled.");
        }
        auditService.recordRequired("RECORDING_ANALYSIS_JOB_CANCELLED", adminId, caseId,
                "ANALYSIS_JOB", jobId, Map.of());
        log.info(
                "business_event=analysis_job_status_changed caseId={} jobId={} status=CANCELLED",
                caseId,
                jobId);
        return findById(caseId, jobId);
    }

    @Transactional
    public RecordingAnalysisJobResponse retry(Long caseId, Long jobId, Long adminId) {
        AnalysisJob job = requireJob(caseId, jobId);
        if (!"FAILED".equals(job.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_STATE_CONFLICT",
                    "Only failed recording analysis jobs can be retried.");
        }
        Recording recording = recordingMapper.findById(job.getRecordingId());
        if (recording == null) {
            throw notFound("Recording was not found.");
        }
        timeRangeCalculator.requireIntersection(
                job.getSearchStartSnapshot(), job.getSearchEndSnapshot(),
                recording.getStartTime(), recording.getEndTime());
        if (analysisJobMapper.retryFailed(caseId, jobId) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_STATE_CONFLICT",
                    "Only failed recording analysis jobs can be retried.");
        }
        auditService.recordRequired("RECORDING_ANALYSIS_JOB_RETRIED", adminId, caseId,
                "ANALYSIS_JOB", jobId, Map.of("previousStatus", job.getStatus()));
        recordingAnalysisJobPublisher.enqueue(jobId, caseId);
        log.info(
                "business_event=analysis_job_retried caseId={} jobId={} status=QUEUED",
                caseId,
                jobId);
        return findById(caseId, jobId);
    }

    private AnalysisJob requireJob(Long caseId, Long jobId) {
        AnalysisJob job = analysisJobMapper.findById(caseId, jobId);
        if (job == null) throw notFound("Recording analysis job was not found.");
        return job;
    }

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
    }

    private ApiException duplicateJob() {
        return new ApiException(HttpStatus.CONFLICT, "RESOURCE_STATE_CONFLICT",
                "An active recording analysis job already exists for this target.");
    }

    private void verifyRecordingObject(String objectKey) {
        try {
            StorageObject object = storageObjectVerifier.stat(objectKey);
            if (object.size() <= 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "STORAGE_OBJECT_INVALID",
                        "Recording object is empty or invalid.");
            }
        } catch (StorageObjectNotFoundException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "STORAGE_OBJECT_NOT_FOUND",
                    "Recording object was not found.");
        } catch (StorageObjectUnavailableException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE",
                    "Recording object could not be verified.");
        }
    }
}
