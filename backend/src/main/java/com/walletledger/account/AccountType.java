package com.walletledger.account;

public enum AccountType {

    /** A customer wallet. Never allowed to go negative. */
    USER_WALLET,

    /** The outside world funding deposits. Goes negative as money enters the system. */
    SYSTEM_FUNDING,

    /** The outside world receiving withdrawals. */
    SYSTEM_PAYOUT
}
