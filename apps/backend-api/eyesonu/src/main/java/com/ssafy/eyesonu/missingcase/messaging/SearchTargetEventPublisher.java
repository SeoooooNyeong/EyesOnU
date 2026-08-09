package com.ssafy.eyesonu.missingcase.messaging;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SearchTargetEventPublisher {

	public static final String EXCHANGE = "search.target.exchange";
	public static final String REALTIME_QUEUE = "search.target.realtime.queue";
	public static final String ROUTING_KEY = "search.target.updated";
	public static final String TARGET_UPDATED = "SEARCH_TARGET_UPDATED";
	public static final String TARGET_DISABLED = "SEARCH_TARGET_DISABLED";
	private static final Logger log = LoggerFactory.getLogger(SearchTargetEventPublisher.class);

	private final RabbitTemplate rabbitTemplate;

	public SearchTargetEventPublisher(RabbitTemplate rabbitTemplate) {
		this.rabbitTemplate = rabbitTemplate;
	}

	public void publishAfterCommit(String eventType, Long caseId, Instant updatedAt) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			throw new IllegalStateException("Search target events must be published inside a transaction");
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				log.debug(
						"search_target_message_registered caseId={} eventType={} status=AFTER_COMMIT",
						caseId,
						eventType);
				publish(eventType, caseId, updatedAt);
			}
		});
	}

	private void publish(String eventType, Long caseId, Instant updatedAt) {
		long startedAt = System.nanoTime();
		SearchTargetEvent event = new SearchTargetEvent(
				UUID.randomUUID().toString(), eventType, caseId, updatedAt, Instant.now());
		try {
			rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event, message -> {
				message.getMessageProperties().setDeliveryMode(
						org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
				return message;
			});
			log.info(
					"business_event=search_target_message_published caseId={} eventType={} status=PUBLISHED elapsedMs={}",
					caseId,
					eventType,
					TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
		}
		catch (RuntimeException exception) {
			log.error(
					"search_target_message_publish_failed caseId={} eventType={} status=FAILED elapsedMs={}",
					caseId,
					eventType,
					TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
					exception);
			throw exception;
		}
	}
}
