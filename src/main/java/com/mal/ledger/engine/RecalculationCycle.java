package com.mal.ledger.engine;

import java.time.LocalDate;

/**
 * One recorded recalculation of a specific account's specific value date.
 *
 * <p>A day's own native close - the first time it is computed, as the stream walks
 * through it in order - is cycle 1. It is not itself logged here; nothing has
 * "recalculated" yet. Every back-dated write that lands on that day afterwards - a
 * fresh credit or debit, a settlement, a reversal - disturbs it again, and each such
 * disturbance is one more cycle, logged here with the ledger record it produced.
 *
 * <p>See {@link LedgerConfig#maxRecalculationCycles()}: once a day's cycle count would
 * exceed that maximum, the write that would have caused it is refused outright
 * ({@link ErrorCode#RECALCULATION_LIMIT_EXCEEDED}) and nothing is appended - so a
 * rejected attempt produces no {@code RecalculationCycle} entry of its own, only an
 * {@link EngineError}.
 *
 * @param transactionId the id of the ledger record this cycle produced
 * @param accountId     the account whose day was recalculated
 * @param valueDate     the day that was recalculated
 * @param cycleNumber   how many times this (account, day) has now been calculated in
 *                      total, native close included (so the first back-dated disturbance
 *                      is cycle 2, not cycle 1)
 */
public record RecalculationCycle(
        String transactionId,
        String accountId,
        LocalDate valueDate,
        int cycleNumber) {

    @Override
    public String toString() {
        return "%s %s vd=%s cycle=%d".formatted(transactionId, accountId, valueDate, cycleNumber);
    }
}
