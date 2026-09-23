package com.mal.ledger.engine;

/** Every rejection the engine can produce. Funds never move on a rejection. */
public enum ErrorCode {
    UNKNOWN_ACCOUNT,
    /** Settlement quoting an auth id the ledger has never seen - rejected on security grounds. */
    UNKNOWN_AUTH,
    AUTH_NOT_APPROVED,
    AUTH_ALREADY_SETTLED,
    SETTLEMENT_EXCEEDS_AUTH,
    INSUFFICIENT_LEDGER_BALANCE,
    /** Hold refused: ledger balance - active holds - requested hold would go negative. */
    INSUFFICIENT_AVAILABLE_BALANCE,
    DUPLICATE_AUTH_ID,
    UNKNOWN_REVERSAL_TARGET,
    ALREADY_REVERSED,
    INVALID_AMOUNT,
    RECOMPUTE_DID_NOT_CONVERGE,
    /** A back-dated write would recalculate a day past LedgerConfig.maxRecalculationCycles. */
    RECALCULATION_LIMIT_EXCEEDED
}
