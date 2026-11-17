
package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.model.OutboxEvent;
import com.fiscalmotion.ledger.repository.OutboxEventRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class OutboxPublisherScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(
                    OutboxPublisherScheduler.class
            );

    private static final String TOPIC_TRANSACTIONS =
            "fiscalmotion.transactions";

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPublisherScheduler(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void publishOutboxEvents() {

        List<OutboxEvent> events =
                outboxEventRepository.findUnprocessedEvents(
                        PageRequest.of(0, 50)
                );

        if (events.isEmpty()) {
            return;
        }

        for (OutboxEvent event : events) {

            try {
                // Wait for Kafka broker acknowledgment.
                kafkaTemplate.send(
                        TOPIC_TRANSACTIONS,
                        event.getAggregateId(),
                        event.getPayload()
                ).get(10, TimeUnit.SECONDS);

                // Executed inside the scheduler's DB transaction.
                event.setProcessed(true);

                log.debug(
                        "Published outbox event {}",
                        event.getId()
                );

            } catch (InterruptedException ex) {

                Thread.currentThread().interrupt();

                throw new IllegalStateException(
                        "Outbox publishing interrupted",
                        ex
                );

            } catch (Exception ex) {

                log.error(
                        "Failed to publish outbox event {}",
                        event.getId(),
                        ex
                );

                throw new IllegalStateException(
                        "Outbox publishing failed",
                        ex
                );
            }
        }

        // Flush changes before the transaction completes.
        outboxEventRepository.flush();
    }
}
