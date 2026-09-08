package com.walletledger.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByOwnerUserId(Long ownerUserId);

    Optional<Account> findByType(AccountType type);
}
