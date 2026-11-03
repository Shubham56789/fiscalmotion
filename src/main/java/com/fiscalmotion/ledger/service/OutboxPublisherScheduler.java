package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.model.OutboxEvent;
import com.fiscalmotion.ledger.repository.OutboxEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OutboxPublisherScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private static final String TOPIC_TRANSACTIONS = "fiscalmotion.transactions";

    public OutboxPublisherScheduler(OutboxEventRepository outboxEventRepository,
                                    KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void publishOutboxEvents() {
        List<OutboxEvent> events = outboxEventRepository.findUnprocessedEvents(PageRequest.of(0, 50));

        for (OutboxEvent event : events) {
            kafkaTemplate.send(TOPIC_TRANSACTIONS, event.getAggregateId(), event.getPayload())
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            event.setProcessed(true);
                            outboxEventRepository.save(event);
                        }
                    });
        }
    }
}