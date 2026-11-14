package com.fiscalmotion.ledger.repository;

import com.fiscalmotion.ledger.model.BulkTransferBatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BulkTransferBatchRepository
        extends JpaRepository<BulkTransferBatch, String> {
}