package com.mal.ledger.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Account details: id, currency, closing ledger amount, closing date, week start date.
 * Precision travels with the account so every store-time rounding is correlative.
 */
public final class Account {

    private final String id;
    private final Currency currency;
    private final LocalDate weekStartDate;
    private final BigDecimal overdraftFee;
    private BigDecimal closingLedgerAmount;
    private LocalDate closingDate;

    public Account(String id, Currency currency, LocalDate weekStartDate, BigDecimal overdraftFee) {
        this.id = id;
        this.currency = currency;
        this.weekStartDate = weekStartDate;
        this.overdraftFee = overdraftFee;
        this.closingLedgerAmount = Money.zero(currency);
        this.closingDate = weekStartDate.minusDays(1);
    }

    public String id() { return id; }
    public Currency currency() { return currency; }
    public int precision() { return currency.precision(); }
    public LocalDate weekStartDate() { return weekStartDate; }
    public BigDecimal overdraftFee() { return overdraftFee; }
    public BigDecimal closingLedgerAmount() { return closingLedgerAmount; }
    public LocalDate closingDate() { return closingDate; }

    public void setClosing(BigDecimal amount, LocalDate date) {
        this.closingLedgerAmount = amount;
        this.closingDate = date;
    }

    @Override
    public String toString() {
        return "%s (%s, %d dp)".formatted(id, currency, precision());
    }
}
