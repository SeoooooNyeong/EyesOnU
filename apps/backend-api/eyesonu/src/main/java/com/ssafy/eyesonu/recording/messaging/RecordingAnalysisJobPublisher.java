package com.ssafy.eyesonu.recording.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import com.ssafy.eyesonu.recording.domain.RecordingAnalysisOutbox;
import com.ssafy.eyesonu.recording.domain.RecordingAnalysisPublishSnapshot;
import com.ssafy.eyesonu.recording.mapper.RecordingAnalysisOutboxMapper;
import com.ssafy.eyesonu.recording.mapper.AnalysisJobMapper;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import jakarta.annotation.PreDestroy;

@Component
public class RecordingAnalysisJobPublisher implements AutoCloseable {

    public static final String EVENT_TYPE = "RECORDING_ANALYSIS_JOB_CREATED";
    public static final String QUEUE = "search.target.recording.queue";
    public static final String ROUTING_KEY = "search.target.recording.created";
    public static final String EXCHANGE = "search.target.exchange";
    public static final String DEAD_LETTER_EXCHANGE = "search.target.dlx";
    public static final String DEAD_LETTER_QUEUE = QUEUE + ".dlq";
    public static final String DEAD_LETTER_ROUTING_KEY = ROUTING_KEY + ".dlq";
    private static final long CONFIRM_TIMEOUT_SECONDS = 5L;
    private static final String TRACE_ID_MDC_KEY = "traceId";
    private static final Logger log = LoggerFactory.getLogger(RecordingAnalysisJobPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final RecordingAnalysisOutboxMapper outboxMapper;
    private final RecordingAnalysisOutboxClaimer outboxClaimer;
    private final AnalysisJobMapper analysisJobMapper;
    private final long claimLeaseSeconds;
    private final Object heartbeatExecutorMonitor = new Object();
    private ScheduledExecutorService heartbeatExecutor;

    public RecordingAnalysisJobPublisher(
            RabbitTemplate rabbitTemplate,
            RecordingAnalysisOutboxMapper outboxMapper,
            RecordingAnalysisOutboxClaimer outboxClaimer,
            AnalysisJobMapper analysisJobMapper,
            @Value("${recording.analysis.outbox.claim-lease-seconds:300}") long claimLeaseSeconds) {
        this.rabbitTemplate = rabbitTemplate;
        this.outboxMapper = outboxMapper;
        this.outboxClaimer = outboxClaimer;
        this.analysisJobMapper = analysisJobMapper;
        this.claimLeaseSeconds = claimLeaseSeconds;
    }

    @PreDestroy
    @Override
    public void close() {
        synchronized (heartbeatExecutorMonitor) {
            if (heartbeatExecutor != null) {
                heartbeatExecutor.shutdownNow();
                heartbeatExecutor = null;
            }
        }
    }

    public void enqueue(Long jobId, Long caseId) {
        RecordingAnalysisPublishSnapshot snapshot =
                analysisJobMapper.findRecordingAnalysisPublishSnapshot(jobId, caseId);
        if (snapshot == null) {
            throw new IllegalStateException("Recording analysis job could not be loaded for publishing: " + jobId);
        }

        RecordingAnalysisOutbox outbox = new RecordingAnalysisOutbox();
        outbox.setCommandId(UUID.randomUUID().toString());
        outbox.setEventType(EVENT_TYPE);
        outbox.setJobId(snapshot.getJobId());
        outbox.setCaseId(snapshot.getCaseId());
        outbox.setRecordingId(snapshot.getRecordingId());
        outbox.setCameraId(snapshot.getCameraId());
        outbox.setCameraCode(snapshot.getCameraCode());
        outbox.setCameraName(snapshot.getCameraName());
        outbox.setRecordingObjectKey(snapshot.getRecordingObjectKey());
        outbox.setPrompt(snapshot.getPrompt());
        outbox.setExclusionPrompt(snapshot.getExclusionPrompt());
        outbox.setSearchStart(snapshot.getSearchStart());
        outbox.setSearchEnd(snapshot.getSearchEnd());
        outbox.setSearchArea(snapshot.getSearchArea());
        outbox.setAttempt(snapshot.getAttempt());
        outbox.setOccurredAt(Instant.now());
        outboxMapper.insert(outbox);
        log.info(
                "business_event=analysis_job_message_queued jobId={} caseId={} status=QUEUED",
                outbox.getJobId(),
                outbox.getCaseId());
    }

    public void publishPending() {
        for (int published = 0; published < 50; published++) {
            ClaimedRecordingAnalysisOutbox claimed = outboxClaimer.claimNext().orElse(null);
            if (claimed == null) {
                return;
            }
            RecordingAnalysisOutbox outbox = claimed.outbox();
            Map<String, String> previousContext = MDC.getCopyOfContextMap();
            MDC.put(TRACE_ID_MDC_KEY, UUID.randomUUID().toString());
            long startedAt = System.nanoTime();
            AtomicBoolean leaseOwned = new AtomicBoolean(true);
            ScheduledFuture<?> heartbeat = null;
            try {
                heartbeat = startHeartbeat(
                        outbox, claimed.claimToken(), leaseOwned);
                RecordingAnalysisJobEvent event = new RecordingAnalysisJobEvent(
                        outbox.getCommandId(), outbox.getEventType(), outbox.getJobId(),
                        outbox.getCaseId(), outbox.getRecordingId(), outbox.getCameraId(),
                        outbox.getCameraCode(), outbox.getCameraName(), outbox.getRecordingObjectKey(),
                        outbox.getAttempt(), outbox.getOccurredAt());
                CorrelationData correlationData = new CorrelationData(outbox.getCommandId());
                ensureLeaseOwned(leaseOwned);
                rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event, message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                }, correlationData);
                ensureLeaseOwned(leaseOwned);
                awaitBrokerAcceptance(correlationData, leaseOwned);
                outboxMapper.markPublished(outbox.getId(), claimed.claimToken(), Instant.now());
                log.info(
                        "business_event=analysis_job_message_published jobId={} caseId={} status=PUBLISHED elapsedMs={}",
                        outbox.getJobId(),
                        outbox.getCaseId(),
                        elapsedMillis(startedAt));
            } catch (LeaseOwnershipLostException exception) {
                log.debug(
                        "analysis_job_message_publish_skipped jobId={} caseId={} status=LEASE_LOST",
                        outbox.getJobId(),
                        outbox.getCaseId());
                return;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                outboxMapper.markFailed(
                        outbox.getId(), claimed.claimToken(),
                        "RabbitMQ publisher confirm was interrupted");
                log.warn(
                        "analysis_job_message_publish_interrupted jobId={} caseId={} status=RETRY elapsedMs={}",
                        outbox.getJobId(),
                        outbox.getCaseId(),
                        elapsedMillis(startedAt),
                        exception);
                return;
            } catch (Exception exception) {
                outboxMapper.markFailed(
                        outbox.getId(), claimed.claimToken(), failureMessage(exception));
                log.error(
                        "analysis_job_message_publish_failed jobId={} caseId={} status=RETRY elapsedMs={}",
                        outbox.getJobId(),
                        outbox.getCaseId(),
                        elapsedMillis(startedAt),
                        exception);
            } finally {
                if (heartbeat != null) {
                    heartbeat.cancel(false);
                }
                MDC.remove(TRACE_ID_MDC_KEY);
                restoreMdc(previousContext);
            }
        }
    }

    private void restoreMdc(Map<String, String> previousContext) {
        if (previousContext == null || previousContext.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousContext);
        }
    }

    private ScheduledFuture<?> startHeartbeat(
            RecordingAnalysisOutbox outbox,
            String claimToken,
            AtomicBoolean leaseOwned) {
        renewLease(outbox.getId(), claimToken, leaseOwned);
        if (!leaseOwned.get()) {
            return heartbeatExecutor().schedule(withMdc(() -> { }), 0, TimeUnit.MILLISECONDS);
        }
        long intervalSeconds = Math.max(1L, claimLeaseSeconds / 3L);
        return heartbeatExecutor().scheduleAtFixedRate(
                withMdc(() -> renewLease(outbox.getId(), claimToken, leaseOwned)),
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS);
    }

    private ScheduledExecutorService heartbeatExecutor() {
        synchronized (heartbeatExecutorMonitor) {
            if (heartbeatExecutor == null || heartbeatExecutor.isShutdown()) {
                heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "recording-analysis-outbox-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return heartbeatExecutor;
        }
    }

    private void renewLease(Long outboxId, String claimToken, AtomicBoolean leaseOwned) {
        try {
            int renewed = outboxMapper.renewLease(
                    outboxId,
                    claimToken,
                    Instant.now().plusSeconds(claimLeaseSeconds));
            if (renewed != 1) {
                leaseOwned.set(false);
                log.debug("analysis_job_outbox_lease_lost outboxId={} status=LEASE_LOST", outboxId);
            }
        } catch (RuntimeException exception) {
            leaseOwned.set(false);
            log.warn(
                    "analysis_job_outbox_lease_renewal_failed outboxId={} status=LEASE_LOST",
                    outboxId,
                    exception);
        }
    }

    private Runnable withMdc(Runnable task) {
        Map<String, String> capturedContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previousContext = MDC.getCopyOfContextMap();
            try {
                if (capturedContext == null || capturedContext.isEmpty()) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(capturedContext);
                }
                task.run();
            } finally {
                if (previousContext == null || previousContext.isEmpty()) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previousContext);
                }
            }
        };
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private void awaitBrokerAcceptance(
            CorrelationData correlationData,
            AtomicBoolean leaseOwned) throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(CONFIRM_TIMEOUT_SECONDS);
        CorrelationData.Confirm confirm;
        while (true) {
            ensureLeaseOwned(leaseOwned);
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new java.util.concurrent.TimeoutException("RabbitMQ publisher confirm timed out");
            }
            try {
                confirm = correlationData.getFuture().get(
                        Math.min(TimeUnit.NANOSECONDS.toMillis(remainingNanos), 200L),
                        TimeUnit.MILLISECONDS);
                break;
            } catch (java.util.concurrent.TimeoutException ignored) {
                // Re-check ownership while waiting for the broker.
            }
        }
        ensureLeaseOwned(leaseOwned);
        if (!confirm.ack()) {
            throw new IllegalStateException("RabbitMQ publisher NACK: " + confirm.reason());
        }
        ReturnedMessage returned = correlationData.getReturned();
        if (returned != null) {
            throw new IllegalStateException("RabbitMQ message was returned: " + returned.getReplyText());
        }
    }

    private void ensureLeaseOwned(AtomicBoolean leaseOwned) {
        if (!leaseOwned.get()) {
            throw new LeaseOwnershipLostException();
        }
    }

    private static class LeaseOwnershipLostException extends RuntimeException {
    }

    private String failureMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
