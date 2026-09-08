package com.walletledger.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByOwnerUserId(Long ownerUserId);

    Optional<Account> findByType(AccountType type);

    /**
     * Emits SELECT ... FOR UPDATE. Written as an explicit query rather than a derived method
     * name: a derived name hides the SQL it generates, and this is the one query in the system
     * whose exact shape decides whether money can be lost.
     * <p>
     * Callers must lock in ascending account id order — see LedgerPostingService.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    /**
     * Returns the id only, deliberately: loading the entity here would be an unlocked read, and
     * an entity already in the persistence context is not necessarily refreshed when a
     * pessimistic lock is later taken on it.
     */
    @Query("select a.id from Account a where a.ownerUserId = :userId")
    Optional<Long> findWalletIdByOwnerUserId(@Param("userId") Long userId);
}
