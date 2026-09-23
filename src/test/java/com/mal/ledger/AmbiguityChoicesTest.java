package com.mal.ledger;

import com.mal.ledger.domain.Currency;
import com.mal.ledger.engine.LedgerConfig;
import com.mal.ledger.events.AccountSpec;
import com.mal.ledger.events.EventStream;
import com.mal.ledger.events.EventType;
import com.mal.ledger.events.Expectations;
import com.mal.ledger.events.LedgerEvent;
import com.mal.ledger.report.StreamRunResult;
import com.mal.ledger.support.Streams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Notes.md flags several rules as ambiguous and asks for a choice backed by rigorous
 * testing. Each choice is a config flag; each test here runs the baseline stream both
 * ways and shows exactly what the reading changes.
 *
 * <p>{@code gradle test --tests '*AmbiguityChoicesTest'}
 */
@DisplayName("Ambiguity choices - both readings, side by side")
class AmbiguityChoicesTest {

    @Test
    @DisplayName("accrual rounding: DOWN gives 0.76, HALF_UP gives 0.78 - Notes.md requires DOWN")
    void accrualRoundingMode() {
        assertEquals(0, new BigDecimal("0.76").compareTo(
                        Streams.run("iteration-1-baseline").engine().capitalizedInterest("ACC001")),
                "465.00 x 0.0004 = 0.186 must accrue 0.18, as Notes.md states for days 4 and 5");

        StreamRunResult halfUp = runBaselineWith(c -> c.accrualRounding(RoundingMode.HALF_UP));
        assertEquals(0, new BigDecimal("0.78").compareTo(halfUp.engine().capitalizedInterest("ACC001")),
                "HALF_UP turns both 0.186 days into 0.19 and credits interest not yet earned");
    }

    @Test
    @DisplayName("accrual remainder: carried into the total (0.76), not discarded from raw (0.77)")
    void accrualRemainderHandling() {
        assertEquals(0, new BigDecimal("0.76").compareTo(
                Streams.run("iteration-1-baseline").engine().capitalizedInterest("ACC001")));

        StreamRunResult discarded = runBaselineWith(c -> c.discardAccrualRemainder(true));
        assertEquals(0, new BigDecimal("0.77").compareTo(discarded.engine().capitalizedInterest("ACC001")),
                "recomputing from raw daily figures credits 0.77, which no longer matches "
                        + "the 0.76 the stored accrual history adds up to");
    }

    @Test
    @DisplayName("interest capitalization excludes its own day - including it is circular")
    void capitalizationWindow() {
        StreamRunResult excluded = Streams.run("iteration-1-baseline");
        assertEquals(0, new BigDecimal("0.76").compareTo(excluded.engine().capitalizedInterest("ACC001")),
                "days 1-5, as Notes.md states");
        assertEquals(0, new BigDecimal("0.004").compareTo(excluded.engine().capitalizedInterest("ACC002")),
                "ACC002 only funded on day 5, so only day 5 accrues - confirming day 6 is excluded");

        StreamRunResult included = runBaselineWith(c -> c.capitalizationExcludesOwnDay(false));
        assertEquals(0, new BigDecimal("0.92").compareTo(included.engine().capitalizedInterest("ACC001")),
                "including day 6 adds its own accrual - but day 6's balance now depends on the "
                        + "credit being computed from it");
        assertEquals(0, new BigDecimal("0.008").compareTo(included.engine().capitalizedInterest("ACC002")),
                "and ACC002 would capitalize 0.008, contradicting the 0.004 Notes.md states");
        assertTrue(included.days().stream().allMatch(d -> d.converged()),
                "the fixed-point loop still terminates, but on a self-referential figure");
    }

    @Test
    @DisplayName("overdraft on days with no movement: Notes.md's figures say no, its prose says yes")
    void overdraftOnQuietDays() {
        // The figures: day 5 closes at -205 and exactly two fees are ever charged
        // (day 2, day 4). Day 5 carries the balance without a fee of its own.
        StreamRunResult byFigures = Streams.run("iteration-1-baseline");
        assertEquals(0, new BigDecimal("-205.00").compareTo(
                byFigures.day(5).accounts().get(0).closingBalance()));
        assertEquals(2, byFigures.engine().overdraftFeeRecordCount("ACC001"));

        // The prose: "false, carries on to day4 and day 5" - i.e. day 5 is charged too.
        StreamRunResult byProse = runBaselineWith(c -> c.overdraftFeeOnDaysWithoutMovement(true));
        assertEquals(0, new BigDecimal("-230.00").compareTo(
                        byProse.day(5).accounts().get(0).closingBalance()),
                "charging the quiet day too takes day 5 to -230, not the -205 Notes.md prints");
        assertEquals(3, byProse.engine().overdraftFeeRecordCount("ACC001"));

        // Fees are permanent now (REJECTED.md R2): day 6's reversal undoes E7's debit in
        // both readings, but it was never going to undo a fee either way. The extra day-5
        // fee under the prose reading is a real, lasting 25 AED the figures reading never
        // charges - the two readings settle permanently apart, not at the same end state.
        assertEquals(0, new BigDecimal("-50.00").compareTo(byFigures.engine().netOverdraftFees("ACC001")));
        assertEquals(0, new BigDecimal("-75.00").compareTo(byProse.engine().netOverdraftFees("ACC001")));
        assertNotEquals(0, byFigures.engine().account("ACC001").closingLedgerAmount()
                        .compareTo(byProse.engine().account("ACC001").closingLedgerAmount()),
                "25 AED apart at the end of the window, not equal");
    }

    @Test
    @DisplayName("assessment order no longer self-corrects a fee - fees are permanent either way")
    void assessmentExcludesOwnDayFee() {
        // This flag used to decide whether a day judged AFTER its own fee could ever
        // climb back to solvent (it could not: 10 - 25 = -15 reads as still overdrawn,
        // trapping the fee alive forever). Now that fees never reverse at all (REJECTED.md
        // R2), that trap cannot arise either way - a fee, once charged, stays regardless
        // of which balance a later day is judged against. The flag still changes what the
        // report *displays* as the assessment basis, but not the outcome.
        EventStream base = hysteresisStream(LedgerConfig.builder()
                .weekStartDate(LocalDate.of(2026, 1, 1)).windowDays(6).capitalizationDay(6).build());
        StreamRunResult excludesOwnFee = Streams.run(base);
        assertEquals(0, new BigDecimal("-15.00").compareTo(
                        excludesOwnFee.engine().dailyAccount("ACC001", 2).closingBalance()),
                "day 2's underlying balance climbs back to +10, but its fee never reverses");

        EventStream alternative = hysteresisStream(base.config().toBuilder()
                .overdraftAssessmentExcludesOwnDayFee(false).build());
        StreamRunResult includesOwnFee = Streams.run(alternative);
        assertEquals(0, new BigDecimal("-15.00").compareTo(
                        includesOwnFee.engine().dailyAccount("ACC001", 2).closingBalance()),
                "identical outcome under the other reading too - neither one reverses a fee");

        assertEquals(0, excludesOwnFee.engine().netOverdraftFees("ACC001")
                .compareTo(includesOwnFee.engine().netOverdraftFees("ACC001")),
                "the two readings no longer diverge at all once a fee has been charged");
    }

    @Test
    @DisplayName("installment slices round DOWN so a split never credits more than instructed")
    void installmentRounding() {
        StreamRunResult down = Streams.run("iteration-1-baseline");
        assertEquals(0, new BigDecimal("10.000").compareTo(
                down.engine().dailyAccount("ACC002", 5).closingBalance()));

        StreamRunResult halfUp = runBaselineWith(c -> c.installmentRounding(RoundingMode.HALF_UP));
        assertEquals(0, new BigDecimal("10.000").compareTo(
                        halfUp.engine().dailyAccount("ACC002", 5).closingBalance()),
                "the balancing entry keeps the total right either way");
        assertEquals("3.333", halfUp.engine().transactionsFor("ACC002").stream()
                        .filter(t -> t.type().name().equals("INSTALLMENT_CREDIT"))
                        .findFirst().orElseThrow().signedAmount().toPlainString(),
                "10/3 = 3.3333 rounds to 3.333 under HALF_UP too; DOWN is what guarantees "
                        + "slice <= total/n for the cases that do round up");

        // 1.000 BHD over 7: 0.142857... HALF_UP would post 0.143 x 7 = 1.001, over the total.
        StreamRunResult sevenWay = Streams.run("iteration-4-precision");
        assertEquals("0.142", sevenWay.engine().transactionsFor("BHD001").stream()
                .filter(t -> t.type().name().equals("INSTALLMENT_CREDIT"))
                .filter(t -> t.valueDate().equals(LocalDate.of(2026, 1, 3)))
                .findFirst().orElseThrow().signedAmount().toPlainString());
    }

    // ------------------------------------------------------------- helpers

    private StreamRunResult runBaselineWith(UnaryOperator<LedgerConfig.Builder> tweak) {
        EventStream base = Streams.load("iteration-1-baseline");
        EventStream variant = new EventStream(
                base.id() + "-variant", base.name(), base.description(),
                tweak.apply(base.config().toBuilder()).build(),
                base.accounts(), base.events(), new Expectations());
        return Streams.run(variant);
    }

    /** Day 2 is charged a fee, then a day-4 back-dated credit lifts it back above zero. */
    private EventStream hysteresisStream(LedgerConfig config) {
        List<LedgerEvent> events = List.of(
                LedgerEvent.builder("H1", 1, "ACC001", EventType.CREDIT).amount("100").build(),
                LedgerEvent.builder("H2", 2, "ACC001", EventType.DEBIT).amount("150").build(),
                LedgerEvent.builder("H3", 3, "ACC001", EventType.CREDIT).amount("300").build(),
                LedgerEvent.builder("H4", 4, "ACC001", EventType.CREDIT).amount("60").valueDay(2)
                        .note("back-dated: lifts day 2 to +10 before its own fee").build());
        return new EventStream("ambiguity-hysteresis", "Fee hysteresis probe", "",
                config, List.of(new AccountSpec("ACC001", Currency.AED)), events, new Expectations());
    }
}
