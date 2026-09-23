package com.mal.ledger.engine;

import com.mal.ledger.domain.Currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

/**
 * Every ambiguity called out in Notes.md is resolved here as an explicit, editable
 * knob so the choice can be tested rather than assumed.
 *
 * <ul>
 *   <li>{@code accrualRounding} - DOWN. Notes.md requires day 4 of 465.00 AED to accrue
 *       0.18 (465 * 0.0004 = 0.186). HALF_UP would give 0.19. DOWN also yields the
 *       "below 0.0049 rounds to 0" behaviour the notes describe, and never credits
 *       interest the balance has not earned.</li>
 *   <li>{@code storeRounding} - HALF_UP for stored ledger amounts.</li>
 *   <li>{@code installmentRounding} - DOWN: every installment must be &lt;= total/n, and
 *       the remainder is posted as a separate BALANCING_ADJUSTMENT (BUFFER_PAY).</li>
 *   <li>{@code capitalizationExcludesOwnDay} - true. The capitalization credit lands on
 *       the capitalization day, so including that day's own accrual would be circular.
 *       Notes.md confirms it: ACC001 totals 0.82 (days 1-5) and ACC002 totals 0.004
 *       (day 5 only).</li>
 *   <li>{@code overdraftAssessmentExcludesOwnDayFee} - true. A day is judged on its
 *       balance <em>before</em> its own fee ("Day 2 closing ledger balance calculated at
 *       end of day 5 before any fee assessed = -370"). Fees from <em>earlier</em> days do
 *       count, which is why day 4 closes at -180 and not -155. This makes assessment
 *       idempotent and non-oscillating.</li>
 * </ul>
 */
public final class LedgerConfig {

    private final LocalDate weekStartDate;
    private final int windowDays;
    private final int capitalizationDay;
    private final BigDecimal dailyInterestRate;
    private final RoundingMode accrualRounding;
    private final RoundingMode storeRounding;
    private final RoundingMode installmentRounding;
    private final boolean capitalizationExcludesOwnDay;
    private final boolean overdraftAssessmentExcludesOwnDayFee;
    private final boolean overdraftFeeOnDaysWithoutMovement;
    private final boolean discardAccrualRemainder;
    private final Map<Currency, BigDecimal> overdraftFees;
    private final int maxRecomputePasses;

    private LedgerConfig(Builder b) {
        this.weekStartDate = b.weekStartDate;
        this.windowDays = b.windowDays;
        this.capitalizationDay = b.capitalizationDay;
        this.dailyInterestRate = b.dailyInterestRate;
        this.accrualRounding = b.accrualRounding;
        this.storeRounding = b.storeRounding;
        this.installmentRounding = b.installmentRounding;
        this.capitalizationExcludesOwnDay = b.capitalizationExcludesOwnDay;
        this.overdraftAssessmentExcludesOwnDayFee = b.overdraftAssessmentExcludesOwnDayFee;
        this.overdraftFeeOnDaysWithoutMovement = b.overdraftFeeOnDaysWithoutMovement;
        this.discardAccrualRemainder = b.discardAccrualRemainder;
        this.overdraftFees = new EnumMap<>(b.overdraftFees);
        this.maxRecomputePasses = b.maxRecomputePasses;
    }

    public static Builder builder() {
        return new Builder();
    }

    public LocalDate weekStartDate() { return weekStartDate; }
    public int windowDays() { return windowDays; }
    public int capitalizationDay() { return capitalizationDay; }
    public BigDecimal dailyInterestRate() { return dailyInterestRate; }
    public RoundingMode accrualRounding() { return accrualRounding; }
    public RoundingMode storeRounding() { return storeRounding; }
    public RoundingMode installmentRounding() { return installmentRounding; }
    public boolean capitalizationExcludesOwnDay() { return capitalizationExcludesOwnDay; }
    public boolean overdraftAssessmentExcludesOwnDayFee() { return overdraftAssessmentExcludesOwnDayFee; }
    public boolean overdraftFeeOnDaysWithoutMovement() { return overdraftFeeOnDaysWithoutMovement; }
    public boolean discardAccrualRemainder() { return discardAccrualRemainder; }
    public int maxRecomputePasses() { return maxRecomputePasses; }

    public LocalDate dateOfDay(int day) {
        return weekStartDate.plusDays(day - 1L);
    }

    public int dayOfDate(LocalDate date) {
        return (int) (date.toEpochDay() - weekStartDate.toEpochDay()) + 1;
    }

    public LocalDate capitalizationDate() {
        return dateOfDay(capitalizationDay);
    }

    public LocalDate lastDate() {
        return dateOfDay(windowDays);
    }

    public BigDecimal overdraftFeeFor(Currency currency) {
        return overdraftFees.getOrDefault(currency, currency.defaultOverdraftFee());
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.weekStartDate = weekStartDate;
        b.windowDays = windowDays;
        b.capitalizationDay = capitalizationDay;
        b.dailyInterestRate = dailyInterestRate;
        b.accrualRounding = accrualRounding;
        b.storeRounding = storeRounding;
        b.installmentRounding = installmentRounding;
        b.capitalizationExcludesOwnDay = capitalizationExcludesOwnDay;
        b.overdraftAssessmentExcludesOwnDayFee = overdraftAssessmentExcludesOwnDayFee;
        b.overdraftFeeOnDaysWithoutMovement = overdraftFeeOnDaysWithoutMovement;
        b.discardAccrualRemainder = discardAccrualRemainder;
        b.overdraftFees.putAll(overdraftFees);
        b.maxRecomputePasses = maxRecomputePasses;
        return b;
    }

    public static final class Builder {
        private LocalDate weekStartDate = LocalDate.of(2026, 1, 1);
        private int windowDays = 6;
        private int capitalizationDay = 6;
        private BigDecimal dailyInterestRate = new BigDecimal("0.0004");
        private RoundingMode accrualRounding = RoundingMode.DOWN;
        private RoundingMode storeRounding = RoundingMode.HALF_UP;
        private RoundingMode installmentRounding = RoundingMode.DOWN;
        private boolean capitalizationExcludesOwnDay = true;
        private boolean overdraftAssessmentExcludesOwnDayFee = true;
        private boolean overdraftFeeOnDaysWithoutMovement = false;
        private boolean discardAccrualRemainder = false;
        private final Map<Currency, BigDecimal> overdraftFees = new EnumMap<>(Currency.class);
        private int maxRecomputePasses = 16;

        public Builder weekStartDate(LocalDate v) { this.weekStartDate = v; return this; }
        public Builder windowDays(int v) { this.windowDays = v; return this; }
        public Builder capitalizationDay(int v) { this.capitalizationDay = v; return this; }
        public Builder dailyInterestRate(BigDecimal v) { this.dailyInterestRate = v; return this; }
        public Builder accrualRounding(RoundingMode v) { this.accrualRounding = v; return this; }
        public Builder storeRounding(RoundingMode v) { this.storeRounding = v; return this; }
        public Builder installmentRounding(RoundingMode v) { this.installmentRounding = v; return this; }
        public Builder capitalizationExcludesOwnDay(boolean v) { this.capitalizationExcludesOwnDay = v; return this; }
        public Builder overdraftAssessmentExcludesOwnDayFee(boolean v) { this.overdraftAssessmentExcludesOwnDayFee = v; return this; }
        public Builder overdraftFeeOnDaysWithoutMovement(boolean v) { this.overdraftFeeOnDaysWithoutMovement = v; return this; }
        public Builder discardAccrualRemainder(boolean v) { this.discardAccrualRemainder = v; return this; }
        public Builder overdraftFee(Currency c, BigDecimal v) { this.overdraftFees.put(c, v); return this; }
        public Builder maxRecomputePasses(int v) { this.maxRecomputePasses = v; return this; }

        public LedgerConfig build() {
            return new LedgerConfig(this);
        }
    }
}
