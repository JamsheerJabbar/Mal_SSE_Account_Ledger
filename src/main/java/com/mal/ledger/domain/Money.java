package com.mal.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rounding helpers. Every value that is *stored* on the ledger passes through
 * {@link #store}; every accrual passes through {@link #accrue}. The two use
 * different rounding modes on purpose - see LedgerConfig.
 */
public final class Money {

    private Money() {
    }

    public static BigDecimal zero(Currency currency) {
        return BigDecimal.ZERO.setScale(currency.precision(), RoundingMode.UNNECESSARY);
    }

    /** Store-time rounding for a ledger amount. */
    public static BigDecimal store(BigDecimal raw, Currency currency, RoundingMode mode) {
        return raw.setScale(currency.precision(), mode);
    }

    /** Rounding applied to a daily interest accrual before it joins the tally. */
    public static BigDecimal accrue(BigDecimal raw, Currency currency, RoundingMode mode) {
        return raw.setScale(currency.precision(), mode);
    }

    public static boolean isNegative(BigDecimal value) {
        return value.signum() < 0;
    }

    public static boolean isPositive(BigDecimal value) {
        return value.signum() > 0;
    }

    public static String format(BigDecimal value, Currency currency) {
        return value.setScale(currency.precision(), RoundingMode.UNNECESSARY).toPlainString();
    }
}
