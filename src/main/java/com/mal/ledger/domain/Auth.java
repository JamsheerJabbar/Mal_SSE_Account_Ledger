package com.mal.ledger.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A hold: money earmarked for a future debit, pending approval/settlement. */
public final class Auth {

    private final String id;
    private final String accountId;
    private final BigDecimal holdAmount;
    private final LocalDate holdValueDate;
    private final LocalDate decisionDate;
    private AuthStatus status;
    private String decisionReason;
    private BigDecimal settledAmount;
    private LocalDate settledOn;

    public Auth(String id, String accountId, BigDecimal holdAmount, LocalDate holdValueDate,
                LocalDate decisionDate, AuthStatus status, String decisionReason) {
        this.id = id;
        this.accountId = accountId;
        this.holdAmount = holdAmount;
        this.holdValueDate = holdValueDate;
        this.decisionDate = decisionDate;
        this.status = status;
        this.decisionReason = decisionReason;
    }

    public String id() { return id; }
    public String accountId() { return accountId; }
    public BigDecimal holdAmount() { return holdAmount; }
    public LocalDate holdValueDate() { return holdValueDate; }
    public LocalDate decisionDate() { return decisionDate; }
    public AuthStatus status() { return status; }
    public String decisionReason() { return decisionReason; }
    public BigDecimal settledAmount() { return settledAmount; }
    public LocalDate settledOn() { return settledOn; }

    public void settle(BigDecimal amount, LocalDate on) {
        this.status = AuthStatus.SETTLED;
        this.settledAmount = amount;
        this.settledOn = on;
        this.decisionReason = "SETTLED";
    }

    /**
     * A hold reduces the available balance from its value date until it settles.
     * It never touches the ledger balance.
     */
    public boolean isActiveOn(LocalDate date) {
        if (status == AuthStatus.APPROVED && !holdValueDate.isAfter(date)) {
            return true;
        }
        // Settled holds still suppress availability up to (not including) the settlement date,
        // after which the real debit takes over.
        return status == AuthStatus.SETTLED
                && !holdValueDate.isAfter(date)
                && settledOn != null && settledOn.isAfter(date);
    }

    @Override
    public String toString() {
        return "%s=%s(hold %s vd=%s)".formatted(id, status, holdAmount.toPlainString(), holdValueDate);
    }
}
