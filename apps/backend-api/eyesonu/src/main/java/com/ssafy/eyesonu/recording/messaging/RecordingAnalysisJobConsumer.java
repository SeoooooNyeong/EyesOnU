package com.ssafy.eyesonu.recording.messaging;

import com.ssafy.eyesonu.recording.service.RecordingAnalysisJobClaimService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Receives recording-analysis commands and atomically claims the referenced
 * job before the worker starts its analysis.
 */
@Component
public class RecordingAnalysisJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(RecordingAnalysisJobConsumer.class);

    private final RecordingAnalysisJobClaimService claimService;

    public RecordingAnalysisJobConsumer(RecordingAnalysisJobClaimService claimService) {
        this.claimService = claimService;
    }

    public void consume(RecordingAnalysisJobEvent event) {
        if (event == null) {
            log.warn("analysis_job_message_received status=INVALID reason=MISSING_EVENT");
            return;
        }
        if (event.jobId() == null) {
            log.warn(
                    "analysis_job_message_received caseId={} status=INVALID reason=MISSING_JOB_ID",
                    event.caseId());
            return;
        }

        log.info(
                "analysis_job_message_received jobId={} caseId={} status=RECEIVED",
                event.jobId(),
                event.caseId());
        try {
            claimService.claim(event.jobId()).ifPresentOrElse(
                    claimed -> log.info(
                            "business_event=analysis_job_claimed jobId={} caseId={} status=RUNNING",
                            claimed.getId(),
                            claimed.getCaseId()),
                    () -> log.debug(
                            "analysis_job_claim_skipped jobId={} caseId={} status=ALREADY_CLAIMED",
                            event.jobId(),
                            event.caseId()));
        }
        catch (RuntimeException exception) {
            log.error(
                    "analysis_job_claim_failed jobId={} caseId={} status=FAILED",
                    event.jobId(),
                    event.caseId(),
                    exception);
            throw exception;
        }
    }
}
