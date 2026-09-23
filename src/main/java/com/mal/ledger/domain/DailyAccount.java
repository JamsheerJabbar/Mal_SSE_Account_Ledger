package com.mal.ledger.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Derived per-day projection of an account. Rebuilt from the append-only journal
 * on every recomputation, so it is always consistent with the records.
 */
public final class DailyAccount {

    private final String accountId;
    private final Currency currency;
    private final LocalDate valueDate;
    private BigDecimal openingBalance;
    private BigDecimal closingBalance;
    /** Closing balance ignoring this day's own overdraft fee records - the assessment basis. */
    private BigDecimal assessmentBalance;
    private BigDecimal interestAccrual;
    /** The same accrual before store-time rounding - kept to show what rounding discarded. */
    private BigDecimal rawInterestAccrual = BigDecimal.ZERO;
    private boolean overdraftEnabled;
    private BigDecimal activeHoldsTotal;
    private BigDecimal availableBalance;
    private final List<Transaction> transactions = new ArrayList<>();

    public DailyAccount(String accountId, Currency currency, LocalDate valueDate) {
        this.accountId = accountId;
        this.currency = currency;
        this.valueDate = valueDate;
        this.openingBalance = Money.zero(currency);
        this.closingBalance = Money.zero(currency);
        this.assessmentBalance = Money.zero(currency);
        this.interestAccrual = Money.zero(currency);
        this.activeHoldsTotal = Money.zero(currency);
        this.availableBalance = Money.zero(currency);
    }

    public String accountId() { return accountId; }
    public Currency currency() { return currency; }
    public LocalDate valueDate() { return valueDate; }
    public BigDecimal openingBalance() { return openingBalance; }
    public BigDecimal closingBalance() { return closingBalance; }
    public BigDecimal assessmentBalance() { return assessmentBalance; }
    public BigDecimal interestAccrual() { return interestAccrual; }
    public BigDecimal rawInterestAccrual() { return rawInterestAccrual; }
    public boolean overdraftEnabled() { return overdraftEnabled; }
    public BigDecimal activeHoldsTotal() { return activeHoldsTotal; }
    public BigDecimal availableBalance() { return availableBalance; }
    public List<Transaction> transactions() { return List.copyOf(transactions); }

    public void setOpeningBalance(BigDecimal v) { this.openingBalance = v; }
    public void setClosingBalance(BigDecimal v) { this.closingBalance = v; }
    public void setAssessmentBalance(BigDecimal v) { this.assessmentBalance = v; }
    public void setInterestAccrual(BigDecimal v) { this.interestAccrual = v; }
    public void setRawInterestAccrual(BigDecimal v) { this.rawInterestAccrual = v; }
    public void setOverdraftEnabled(boolean v) { this.overdraftEnabled = v; }
    public void setActiveHoldsTotal(BigDecimal v) { this.activeHoldsTotal = v; }
    public void setAvailableBalance(BigDecimal v) { this.availableBalance = v; }

    public void replaceTransactions(List<Transaction> txns) {
        transactions.clear();
        transactions.addAll(txns);
    }

    /** True when this day's own ledger movement (fees aside) left it overdrawn. */
    public boolean inOverdraft() {
        return closingBalance.signum() < 0;
    }

    /** Closing balance with this day's interest capitalization stripped back out. */
    public BigDecimal closingBalanceExcludingInterest() {
        BigDecimal interest = Money.zero(currency);
        for (Transaction t : transactions) {
            if (t.type().isInterestRecord()) {
                interest = interest.add(t.signedAmount());
            }
        }
        return closingBalance.subtract(interest);
    }
}
