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
     * A hold reduces the available balance while it is approved and unsettled. It never
     * touches the ledger balance. No date constraint: an approved hold counts from the
     * moment it is approved, and stops counting the moment it settles or is rejected -
     * its own status is the only thing that matters.
     */
    public boolean isActive() {
        return status == AuthStatus.APPROVED;
    }

    @Override
    public String toString() {
        return "%s=%s(hold %s vd=%s)".formatted(id, status, holdAmount.toPlainString(), holdValueDate);
    }
}
