package com.fiscalmotion.ledger.repository;

import com.fiscalmotion.ledger.model.BulkTransferItem;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BulkTransferItemRepository
        extends JpaRepository<BulkTransferItem, UUID> {

    List<BulkTransferItem> findByBatch_BatchIdOrderByRowNumber(
            String batchId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT i
            FROM BulkTransferItem i
            WHERE i.id = :id
            """)
    Optional<BulkTransferItem> findByIdForUpdate(
            @Param("id") UUID id
    );
}