package com.walletledger.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    Optional<LedgerTransaction> findByPublicId(UUID publicId);
}
