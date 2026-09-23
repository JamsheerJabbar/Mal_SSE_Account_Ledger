package com.mal.ledger;

import com.mal.ledger.domain.Currency;
import com.mal.ledger.domain.Transaction;
import com.mal.ledger.domain.TransactionType;
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
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test cases for the two ambiguities already written down in {@code AMBIGUITIES.md}
 * before this class existed:
 *
 * <ol>
 *   <li>A day that closes negative and gets an overdraft fee can also end up carrying
 *       an interest record for that same day, once a later reversal forces a recompute.</li>
 *   <li>Reverting or updating a day-2 transaction on day 8 forces the interest to be
 *       recalculated and the old total reverted, and can take more than one recompute
 *       pass to settle.</li>
 * </ol>
 *
 * <p>{@code mvn test -Dtest=WrittenAmbiguitiesTest}
 */
@DisplayName("Ambiguities written in AMBIGUITIES.md")
class WrittenAmbiguitiesTest {

    // =====================================================================
    // Ambiguity 1: an overdraft fee and an interest record on the same day
    // =====================================================================

    @Test
    @DisplayName("a fee and an interest record can land on the same day's value date")
    void overdraftFeeAndInterestCoexistOnTheSameDay() {
        // 3-day window, capitalization on day 3.
        // Day 1: credit 100 -> closes positive, accrues 0.04.
        // Day 2: debit 500 -> closes at -400, gets a -25.00 fee, closes at -425.00.
        // Day 3: no transaction of its own. The capitalization pass credits 0.04
        // interest (days 1-2, since day 3 excludes its own accrual) onto day 3 - but
        // that credit is itself ledger movement, and -425.00 + 0.04 is still negative,
        // so day 3 also gets its own overdraft fee. Both records end up with the same
        // value date: day 3.
        EventStream stream = twoDayOverdraftThenCapitalize();
        StreamRunResult result = Streams.run(stream);

        LocalDate day3 = stream.config().dateOfDay(3);
        List<Transaction> onDay3 = result.engine().transactionsFor("ACC001").stream()
                .filter(t -> t.valueDate().equals(day3))
                .toList();

        boolean hasFee = onDay3.stream().anyMatch(t -> t.type() == TransactionType.OVERDRAFT_FEE);
        boolean hasInterest = onDay3.stream().anyMatch(t -> t.type() == TransactionType.INTEREST_CAPITALIZATION);
        assertTrue(hasFee, "day 3 should carry its own overdraft fee: " + onDay3);
        assertTrue(hasInterest, "day 3 should also carry the interest capitalization: " + onDay3);

        assertEquals(0, new BigDecimal("0.04").compareTo(result.engine().capitalizedInterest("ACC001")),
                "interest is earned on days 1-2 only (100 x 0.0004 + 0 for the negative day 2)");
        assertEquals(0, new BigDecimal("-449.96").compareTo(
                        result.engine().dailyAccount("ACC001", 3).closingBalance()),
                "-425.00 opening, +0.04 interest credited, then -25.00 for day 3's own fee");
        assertEquals(2, result.engine().overdraftFeeRecordCount("ACC001"),
                "day 2 is charged once for its own debit, and day 3 is charged again, "
                        + "separately, once the interest credit still leaves it negative");
    }

    @Test
    @DisplayName("the same coexistence happens for real in the day-7 reversal cascade")
    void sameCoexistenceInTheShippedRetroCascadeStream() {
        // iteration-2-retro-cascade: a day-7 back-dated debit drags day 6 (the
        // capitalization day) negative. Day 6 ends up with an OVERDRAFT_FEE and both an
        // INTEREST_CAPITALIZATION_REVERSAL and a fresh INTEREST_CAPITALIZATION, all
        // sharing day 6's value date - exactly the ambiguity, arising from a real stream
        // already shipped in event-streams/, not a constructed example.
        StreamRunResult result = Streams.run("iteration-2-retro-cascade");
        LocalDate day6 = result.stream().config().dateOfDay(6);

        List<Transaction> onDay6 = result.engine().transactionsFor("ACC001").stream()
                .filter(t -> t.valueDate().equals(day6))
                .toList();

        assertTrue(onDay6.stream().anyMatch(t -> t.type() == TransactionType.OVERDRAFT_FEE),
                "day 6 should have taken its own fee once the day-7 debit dragged it negative");
        assertTrue(onDay6.stream().anyMatch(t -> t.type() == TransactionType.INTEREST_CAPITALIZATION_REVERSAL),
                "the interest originally credited on day 6 must be reversed, not patched");
        assertTrue(onDay6.stream().anyMatch(t -> t.type() == TransactionType.INTEREST_CAPITALIZATION),
                "and a fresh capitalization posted - both share day 6's value date with the fee");
    }

    private EventStream twoDayOverdraftThenCapitalize() {
        LedgerConfig config = LedgerConfig.builder()
                .weekStartDate(LocalDate.of(2026, 2, 1))
                .windowDays(3)
                .capitalizationDay(3)
                .build();
        List<LedgerEvent> events = List.of(
                LedgerEvent.builder("A1", 1, "ACC001", EventType.CREDIT).amount("100").build(),
                LedgerEvent.builder("A2", 2, "ACC001", EventType.DEBIT).amount("500")
                        .note("closes day 2 at -400, taking a -25.00 fee").build());
        return new EventStream("ambiguity-fee-and-interest-same-day",
                "Overdraft fee and interest record on the same value date", "",
                config, List.of(new AccountSpec("ACC001", Currency.AED)), events, new Expectations());
    }

    // =====================================================================
    // Ambiguity 2: a day-2 correction landing on day 8 recalculates interest,
    // reverts the old total, and can take more than one pass
    // =====================================================================

    @Test
    @DisplayName("reverting a day-2 transaction on day 8 recalculates interest and reverts the old total")
    void day2CorrectionOnDay8RecalculatesInterest() {
        EventStream stream = day2DebitReversedOnDay8();
        StreamRunResult result = Streams.run(stream);

        // Before the reversal: day 2's debit stood, so days 2-6 closed at 900 and the
        // window capitalized 0.40 + 0.36 x 4 = 1.84 on day 6.
        // After E3 reverts it on day 8, value date day 2: days 2-6 all close 100 higher
        // (1000 instead of 900), so the correct interest for days 1-5 is 0.40 x 5 = 2.00 -
        // not the 1.84 that was already credited.
        assertEquals(0, new BigDecimal("1000.00").compareTo(
                        result.engine().dailyAccount("ACC001", 2).closingBalance()),
                "day 2's debit is reverted, so day 2 is back to the day-1 balance");

        List<Transaction> interestRecords = result.engine().transactionsFor("ACC001").stream()
                .filter(t -> t.type().isInterestRecord())
                .toList();
        assertEquals(1, interestRecords.stream()
                        .filter(t -> t.type() == TransactionType.INTEREST_CAPITALIZATION_REVERSAL).count(),
                "the old total must be reverted, not silently replaced: " + interestRecords);
        assertEquals(0, new BigDecimal("-1.84").compareTo(interestRecords.stream()
                        .filter(t -> t.type() == TransactionType.INTEREST_CAPITALIZATION_REVERSAL)
                        .findFirst().orElseThrow().signedAmount()),
                "it reverses exactly the 1.84 that was originally credited");
        assertEquals(2, interestRecords.stream()
                        .filter(t -> t.type() == TransactionType.INTEREST_CAPITALIZATION).count(),
                "the original 1.84 credit is never deleted (append-only) - a second, "
                        + "corrected credit is posted alongside it: " + interestRecords);

        assertEquals(0, new BigDecimal("2.00").compareTo(result.engine().capitalizedInterest("ACC001")),
                "net capitalized interest is exactly what days 1-5 earn at the corrected 1000 balance");
        assertEquals(0, new BigDecimal("1002.00").compareTo(
                        result.engine().dailyAccount("ACC001", 8).closingBalance()),
                "1000 principal plus the corrected 2.00 interest - as if it had been right from day 1");
    }

    @Test
    @DisplayName("the day-8 recompute takes more than one pass to settle")
    void day2CorrectionOnDay8TakesMultiplePasses() {
        EventStream stream = day2DebitReversedOnDay8();
        StreamRunResult result = Streams.run(stream);

        // The reversal lands with a back-dated value date (day 2, posted day 8), so it
        // is reconciled intra-day; closing day 8 then has to rebuild every day's balance
        // AND reconcile the interest capitalization against the now-different accrual
        // history - each depends on the other, so it takes more than a single pass.
        assertTrue(result.day(8).recomputePasses() >= 2,
                "day 8 should need multiple recompute passes: rebuilding the days 2-6 "
                        + "balances and reconciling the interest they now support are two "
                        + "separate, dependent steps");
        assertTrue(result.days().stream().allMatch(d -> d.converged()),
                "however many passes it takes, it must still reach a fixed point");
        assertTrue(result.day(8).restatedHistory(), "day 8 must be flagged as having restated history");
    }

    private EventStream day2DebitReversedOnDay8() {
        LedgerConfig config = LedgerConfig.builder()
                .weekStartDate(LocalDate.of(2026, 3, 1))
                .windowDays(8)
                .capitalizationDay(6)
                .build();
        List<LedgerEvent> events = List.of(
                LedgerEvent.builder("B1", 1, "ACC001", EventType.CREDIT).amount("1000").build(),
                LedgerEvent.builder("B2", 2, "ACC001", EventType.DEBIT).amount("100")
                        .note("the day-2 transaction this ambiguity is about").build(),
                LedgerEvent.builder("B3", 8, "ACC001", EventType.REVERSAL).target("B2").valueDay(2)
                        .note("reverted on day 8, value date day 2").build());
        return new EventStream("ambiguity-day2-reversal-on-day8",
                "Day-2 transaction reverted on day 8", "",
                config, List.of(new AccountSpec("ACC001", Currency.AED)), events, new Expectations());
    }
}
