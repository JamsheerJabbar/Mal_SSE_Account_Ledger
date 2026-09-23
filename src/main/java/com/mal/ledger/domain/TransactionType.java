package com.mal.ledger.domain;

/**
 * Ledger records are append only. A correction is never an update - it is a new
 * record of one of the *_REVERSAL types pointing back at the record it undoes.
 */
public enum TransactionType {
    CREDIT(+1, false),
    DEBIT(-1, false),
    INSTALLMENT_CREDIT(+1, false),
    /** Remainder of an installment split, tagged BUFFER_PAY. */
    BALANCING_ADJUSTMENT(+1, false),
    SETTLEMENT(-1, false),
    REVERSAL(0, true),
    OVERDRAFT_FEE(-1, false),
    OVERDRAFT_FEE_REVERSAL(+1, true),
    INTEREST_CAPITALIZATION(+1, false),
    INTEREST_CAPITALIZATION_REVERSAL(-1, true);

    private final int sign;
    private final boolean corrective;

    TransactionType(int sign, boolean corrective) {
        this.sign = sign;
        this.corrective = corrective;
    }

    /** 0 means "sign is carried by the record itself" (REVERSAL mirrors its target). */
    public int sign() {
        return sign;
    }

    public boolean isCorrective() {
        return corrective;
    }

    public boolean isOverdraftFeeRecord() {
        return this == OVERDRAFT_FEE || this == OVERDRAFT_FEE_REVERSAL;
    }

    public boolean isInterestRecord() {
        return this == INTEREST_CAPITALIZATION || this == INTEREST_CAPITALIZATION_REVERSAL;
    }
}
