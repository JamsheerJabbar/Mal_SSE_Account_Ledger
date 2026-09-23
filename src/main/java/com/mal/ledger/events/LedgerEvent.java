package com.mal.ledger.events;

import java.math.BigDecimal;

/**
 * One instruction on the stream.
 *
 * @param label       stable handle (E1, E2, ...) - reversals target this
 * @param postingDay  the day the instruction arrives (1-based)
 * @param valueDay    the day the money belongs to; may be earlier than postingDay
 * @param accountId   account the instruction applies to
 * @param type        instruction type
 * @param amount      gross amount, unsigned
 * @param authId      auth handle for AUTH / SETTLEMENT
 * @param targetLabel label of the event being reversed, for REVERSAL
 * @param installments number of slices for INSTALLMENT_CREDIT
 * @param note        free text carried into the report
 */
public record LedgerEvent(
        String label,
        int postingDay,
        int valueDay,
        String accountId,
        EventType type,
        BigDecimal amount,
        String authId,
        String targetLabel,
        int installments,
        String note) {

    public static Builder builder(String label, int postingDay, String accountId, EventType type) {
        return new Builder(label, postingDay, accountId, type);
    }

    public boolean isBackdated() {
        return valueDay < postingDay;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(label).append(" d").append(postingDay).append(' ')
          .append(accountId).append(' ').append(type);
        if (amount != null) sb.append(' ').append(amount.toPlainString());
        if (authId != null) sb.append(" auth=").append(authId);
        if (targetLabel != null) sb.append(" target=").append(targetLabel);
        if (installments > 1) sb.append(" x").append(installments);
        sb.append(" vd=d").append(valueDay);
        return sb.toString();
    }

    public static final class Builder {
        private final String label;
        private final int postingDay;
        private final String accountId;
        private final EventType type;
        private int valueDay;
        private BigDecimal amount;
        private String authId;
        private String targetLabel;
        private int installments = 1;
        private String note;

        private Builder(String label, int postingDay, String accountId, EventType type) {
            this.label = label;
            this.postingDay = postingDay;
            this.accountId = accountId;
            this.type = type;
            this.valueDay = postingDay;
        }

        public Builder valueDay(int v) { this.valueDay = v; return this; }
        public Builder amount(String v) { this.amount = new BigDecimal(v); return this; }
        public Builder amount(BigDecimal v) { this.amount = v; return this; }
        public Builder authId(String v) { this.authId = v; return this; }
        public Builder target(String v) { this.targetLabel = v; return this; }
        public Builder installments(int v) { this.installments = v; return this; }
        public Builder note(String v) { this.note = v; return this; }

        public LedgerEvent build() {
            return new LedgerEvent(label, postingDay, valueDay, accountId, type,
                    amount, authId, targetLabel, installments, note);
        }
    }
}
