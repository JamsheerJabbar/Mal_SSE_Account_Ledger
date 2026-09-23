package com.mal.ledger.domain;

import java.math.BigDecimal;

/**
 * Currency denomination hash: currency -> precision (decimal places) and its
 * default overdraft fee. Precision is stored on the account and is used for
 * every store-time rounding of that account's money.
 */
public enum Currency {
    AED(2, "25.00"),
    BHD(3, "2.500");

    private final int precision;
    private final BigDecimal defaultOverdraftFee;

    Currency(int precision, String defaultOverdraftFee) {
        this.precision = precision;
        this.defaultOverdraftFee = new BigDecimal(defaultOverdraftFee);
    }

    public int precision() {
        return precision;
    }

    public BigDecimal defaultOverdraftFee() {
        return defaultOverdraftFee;
    }

    public static Currency of(String code) {
        return Currency.valueOf(code.trim().toUpperCase());
    }
}
