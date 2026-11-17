
package com.fiscalmotion.ledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class SettlementConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(SettlementConsumer.class);

    private final ObjectMapper objectMapper;

    public SettlementConsumer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "fiscalmotion.transactions",
            groupId = "settlement-group"
    )
    public void consumeTransactionEvent(String messagePayload) {

        try {
            JsonNode node = objectMapper.readTree(messagePayload);

            JsonNode idNode = node.path("transactionId");

            if (!idNode.isTextual()
                    || idNode.asText().isBlank()) {
                throw new IllegalArgumentException(
                        "Missing transactionId"
                );
            }

            UUID transactionId =
                    UUID.fromString(idNode.asText());

            JsonNode statusNode = node.path("status");

            if (!"COMPLETED".equals(statusNode.asText())) {
                throw new IllegalArgumentException(
                        "Unsupported transaction status"
                );
            }

            log.info(
                    "Transaction event received and validated: {}",
                    transactionId
            );

            // This consumer validates and observes events.
            // It does not perform financial settlement.

        } catch (Exception ex) {

            log.error(
                    "Failed to process transaction event",
                    ex
            );

            // Allow Kafka listener error handling to run.
            throw new IllegalStateException(
                    "Transaction event processing failed",
                    ex
            );
        }
    }
}
