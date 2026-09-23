package com.mal.ledger.report;

import com.mal.ledger.domain.Currency;

import java.math.BigDecimal;
import java.util.List;

/** One account's ledger as it reads at the end of a processing day. */
public record AccountDayView(
        String accountId,
        Currency currency,
        BigDecimal closingBalance,
        BigDecimal closingBalanceExcludingInterest,
        BigDecimal availableBalance,
        BigDecimal activeHolds,
        BigDecimal accrualToday,
        BigDecimal accrualToDate,
        BigDecimal capitalizedInterest,
        BigDecimal netOverdraftFees,
        long overdraftFeeCount,
        boolean overdraftEnabled,
        List<LedgerRow> history,
        List<String> authStates) {

    /** A single (possibly restated) day in the account's ledger history. */
    public record LedgerRow(
            int day,
            BigDecimal opening,
            BigDecimal assessmentBalance,
            BigDecimal closing,
            BigDecimal holds,
            BigDecimal available,
            BigDecimal accrual,
            boolean overdraftFeeActive) {
    }
}
