package com.fiscalmotion.ledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fiscalmotion.ledger.repository.SettlementSummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class SettlementConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementConsumer.class);
    private final SettlementSummaryRepository summaryRepository;
    private final ObjectMapper objectMapper = new ObjectMapper(); // Instantiated directly

    public SettlementConsumer(SettlementSummaryRepository summaryRepository) {
        this.summaryRepository = summaryRepository;
    }

    @KafkaListener(topics = "fiscalmotion.transactions", groupId = "settlement-group")
    @Transactional
    public void consumeTransactionEvent(String messagePayload) {
        try {
            log.info("Received Outbox Event from Kafka: {}", messagePayload);
            JsonNode node = objectMapper.readTree(messagePayload);

            String transactionIdStr = node.get("transactionId").asText();
            UUID transactionId = UUID.fromString(transactionIdStr);

            log.info("Successfully processed settlement event for transaction: {}", transactionId);

        } catch (Exception e) {
            log.error("Failed to process settlement event: {}", messagePayload, e);
        }
    }
}