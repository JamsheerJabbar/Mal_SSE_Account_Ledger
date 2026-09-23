package com.mal.ledger.events;

public enum EventType {
    CREDIT,
    DEBIT,
    /** Credit split into N equal installments, remainder posted as BUFFER_PAY. */
    INSTALLMENT_CREDIT,
    /** Hold request. Approved only if ledger balance - active holds - requested >= 0. */
    AUTH,
    /** Settlement against a previously approved hold. */
    SETTLEMENT,
    /** Append-only correction: mirrors the target record, never mutates it. */
    REVERSAL
}
