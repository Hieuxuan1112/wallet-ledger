package com.walletledger.statement;

import com.walletledger.auth.AuthenticatedUser;
import com.walletledger.ledger.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/statement")
public class StatementController {

    private final StatementService statement;

    public StatementController(StatementService statement) {
        this.statement = statement;
    }

    @GetMapping
    public Page<StatementEntryView> get(@AuthenticationPrincipal AuthenticatedUser user,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                        @RequestParam(required = false) TransactionType type,
                                        Pageable pageable) {
        return statement.forUser(user.id(), from, to, type, pageable);
    }
}
