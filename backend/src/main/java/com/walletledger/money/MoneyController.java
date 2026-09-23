package com.walletledger.money;

import com.walletledger.auth.AuthenticatedUser;
import com.walletledger.idempotency.IdempotencyPayloadCodec;
import com.walletledger.idempotency.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class MoneyController {

    private static final String DEPOSITS = "POST /api/v1/wallet/deposits";
    private static final String WITHDRAWALS = "POST /api/v1/wallet/withdrawals";
    private static final String TRANSFERS = "POST /api/v1/transfers";
    private static final String REFUND = "POST /api/v1/transactions/refund";

    private final MoneyService money;
    private final IdempotencyService idempotency;
    private final IdempotencyPayloadCodec codec;
    private final RefundService refunds;

    public MoneyController(MoneyService money, IdempotencyService idempotency,
                           IdempotencyPayloadCodec codec, RefundService refunds) {
        this.money = money;
        this.idempotency = idempotency;
        this.codec = codec;
        this.refunds = refunds;
    }

    @PostMapping("/wallet/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionView deposit(@AuthenticationPrincipal AuthenticatedUser user,
                                   @RequestHeader("Idempotency-Key") String key,
                                   @Valid @RequestBody AmountRequest request) {
        return idempotency.execute(user.id(), key, DEPOSITS, codec.canonicalise(request),
                () -> money.deposit(user.id(), request.amount(), request.description()));
    }

    @PostMapping("/wallet/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionView withdraw(@AuthenticationPrincipal AuthenticatedUser user,
                                    @RequestHeader("Idempotency-Key") String key,
                                    @Valid @RequestBody AmountRequest request) {
        return idempotency.execute(user.id(), key, WITHDRAWALS, codec.canonicalise(request),
                () -> money.withdraw(user.id(), request.amount(), request.description()));
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionView transfer(@AuthenticationPrincipal AuthenticatedUser user,
                                    @RequestHeader("Idempotency-Key") String key,
                                    @Valid @RequestBody TransferRequest request) {
        return idempotency.execute(user.id(), key, TRANSFERS, codec.canonicalise(request),
                () -> money.transfer(user.id(), request.toUsername(), request.amount(),
                        request.description()));
    }

    @PostMapping("/transactions/{publicId}/refund")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionView refund(@AuthenticationPrincipal AuthenticatedUser user,
                                  @RequestHeader("Idempotency-Key") String key,
                                  @PathVariable UUID publicId) {
        return idempotency.execute(user.id(), key, REFUND, publicId.toString(),
                () -> refunds.refund(user.id(), publicId, "refund of " + publicId));
    }
}
