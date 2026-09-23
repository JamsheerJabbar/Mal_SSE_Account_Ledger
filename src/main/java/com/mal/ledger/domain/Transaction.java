package com.mal.ledger.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable, append-only ledger record.
 *
 * @param id          monotonic ledger id (T1, T2, ...)
 * @param accountId   owning account
 * @param type        record type
 * @param signedAmount already rounded to the account currency precision; sign carries direction
 * @param valueDate   the day the money belongs to (may be back-dated relative to postedDate)
 * @param postedDate  the day the record was appended to the journal
 * @param sourceLabel the event label that produced it (E1..En), or a system tag
 * @param reference   auth id / reversed transaction id / free tag
 * @param tag         optional classification, e.g. BUFFER_PAY
 */
public record Transaction(
        String id,
        String accountId,
        TransactionType type,
        BigDecimal signedAmount,
        LocalDate valueDate,
        LocalDate postedDate,
        String sourceLabel,
        String reference,
        String tag) {

    public BigDecimal grossAmount() {
        return signedAmount.abs();
    }

    public boolean isSystemGenerated() {
        return type.isOverdraftFeeRecord() || type.isInterestRecord();
    }

    @Override
    public String toString() {
        return "%s %s %s %s vd=%s posted=%s%s".formatted(
                id, accountId, type, signedAmount.toPlainString(), valueDate, postedDate,
                reference == null ? "" : " ref=" + reference);
    }
}
