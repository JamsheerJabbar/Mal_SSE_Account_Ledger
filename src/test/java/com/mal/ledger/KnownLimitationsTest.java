package com.mal.ledger;

import com.mal.ledger.domain.AuthStatus;
import com.mal.ledger.domain.Currency;
import com.mal.ledger.engine.ErrorCode;
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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Known, accepted gaps in the current design - failing on purpose, not by accident.
 *
 * <p>Each test here documents one place where the system's actual behaviour is not what
 * a naive reading of the non-negotiables would expect, with a pointer to the design
 * decision that made it that way. These are left failing rather than fixed or deleted,
 * so the gap stays visible in the one place a reader is guaranteed to look: a red test.
 * See {@code README.md} "Known failing test" and {@code ARCHITECTURE.md} §5.
 *
 * <p>Independently executable: {@code mvn test -Dtest=KnownLimitationsTest}
 * (expected result: 3 tests, 3 failures - one {@code assertAll} of 4 checks with 1
 * failing, and two single-assertion failures that share one scenario).
 */
@DisplayName("Known limitations (intentionally failing)")
class KnownLimitationsTest {

    /**
     * REJECTED.md R5 disabled the "settlement amount &lt;= ledger balance" check on the
     * grounds that an approved hold is a guarantee, and re-checking the current balance
     * at settlement time would punish the customer for something that happened after
     * their auth was already confirmed. R5's own "what would change my mind" asks for
     * exactly this case: a fee lands on the ledger <em>between</em> approval and
     * settlement, and the settlement is let through anyway - not against the 100 the
     * account had when the hold was approved, but against a balance the fee has since
     * made unable to cover it. It goes through silently, and immediately earns the
     * account a second overdraft fee of its own.
     *
     * <p>Three of the four checks below describe that actual, current, deliberately
     * chosen behaviour and pass. The fourth checks for the textbook-safe alternative -
     * that the ledger refuses to settle for more than it currently holds - and fails,
     * because that check is the one R5 disabled.
     */
    @Test
    @DisplayName("KNOWN GAP (REJECTED.md R5) - a settlement is not re-checked against a "
            + "balance an intervening fee has since made insufficient")
    void settlementIsNotReRefusedAfterAnInterveningFee() {
        StreamRunResult result = Streams.run(interveningFeeScenario());

        assertAll("day 1: credit 100, hold 100 (exactly zero headroom, approved)",
                () -> assertEquals(AuthStatus.SETTLED, result.engine().auths().get("authX").status(),
                        "the auth settles - its approval is never revoked by a later fee"),
                () -> assertEquals(0, new BigDecimal("-125.00").compareTo(
                                result.engine().dailyAccount("ACC001", 1).closingBalance()),
                        "day 2's back-dated 200 debit plus day 1's own -25 fee correctly "
                                + "leaves the ledger at -125 before the settlement arrives"),
                () -> assertEquals(0, new BigDecimal("-250.00").compareTo(
                                result.engine().dailyAccount("ACC001", 3).closingBalance()),
                        "the 100 settlement is let through against a ledger that only holds "
                                + "-125 - taking it to -225, which is itself negative enough to "
                                + "earn a second, independent overdraft fee: -250"),
                () -> assertTrue(result.errors().stream().anyMatch(e ->
                                e.eventLabel().equals("E4") && e.code() == ErrorCode.INSUFFICIENT_LEDGER_BALANCE),
                        "EXPECTED TO FAIL: a settlement should be refused when the ledger "
                                + "cannot actually cover it at settlement time. It isn't - "
                                + "LedgerEngine.settle() has this exact check commented out "
                                + "(REJECTED.md R5) - so no such rejection is ever recorded and "
                                + "the -250 above stands uncontested."));
    }

    /** Day 1: credit 100, hold 100 (approved). Day 2: back-dated 200 debit into day 1
     *  (fee). Day 3: the hold settles for 100 anyway. */
    private EventStream interveningFeeScenario() {
        LedgerConfig config = LedgerConfig.builder()
                .weekStartDate(LocalDate.of(2026, 5, 1)).windowDays(3).capitalizationDay(3).build();
        List<LedgerEvent> events = List.of(
                LedgerEvent.builder("E1", 1, "ACC001", EventType.CREDIT).amount("100").build(),
                LedgerEvent.builder("E2", 1, "ACC001", EventType.AUTH).amount("100").authId("authX")
                        .note("approved: 100 - 0 - 100 = 0 exactly").build(),
                LedgerEvent.builder("E3", 2, "ACC001", EventType.DEBIT).amount("200").valueDay(1)
                        .note("back-dated: drives day 1 to -100, then -125 with its fee").build(),
                LedgerEvent.builder("E4", 3, "ACC001", EventType.SETTLEMENT).amount("100").authId("authX")
                        .note("authX is still approved and unsettled - nothing re-checks the "
                                + "ledger balance, which can no longer actually cover it").build());
        return new EventStream("known-gap-intervening-fee",
                "Settlement not re-refused after an intervening fee", "",
                config, List.of(new AccountSpec("ACC001", Currency.AED)), events, new Expectations());
    }

    /**
     * {@code maxRecalculationCycles} exists to bound scale, not to judge a correction's
     * legitimacy (ARCHITECTURE.md §2.12) - it counts touches to a day, not whether each
     * touch was a good idea. That means a perfectly ordinary sequence of business
     * corrections - a charge, a refund posted for the wrong amount, reversing that
     * mistake, then posting the *correct* refund - can still get its final, correct step
     * refused, purely because it is the 4th touch to the same day, with nothing in the
     * transaction itself wrong at all.
     *
     * <p>The refused write is not a hypothetical: {@link #legitimateCorrectionChainScenario}
     * builds exactly this sequence, and every step up to the last is accepted without
     * complaint - E5, the correct refund, is the only one refused, for depth alone.
     */
    @Test
    @DisplayName("KNOWN GAP (ARCHITECTURE.md §2.12) - a 4th, entirely legitimate correction "
            + "is refused for depth alone, not for anything wrong with it")
    void legitimateFourthCorrectionIsRefusedForScaleAlone() {
        StreamRunResult result = Streams.run(legitimateCorrectionChainScenario());

        assertTrue(result.errors().stream().noneMatch(e -> e.code() == ErrorCode.RECALCULATION_LIMIT_EXCEEDED),
                "EXPECTED TO FAIL: E5 posts the correct refund amount after a documented "
                        + "billing error and its reversal - a transaction with nothing wrong "
                        + "with it on its own merits. It is refused anyway "
                        + "(RECALCULATION_LIMIT_EXCEEDED), because day 2 has already been "
                        + "touched three times and the limit does not distinguish a "
                        + "legitimate correction chain from a runaway one.");
    }

    /**
     * The direct, quantified consequence of the refusal above: with E5 rejected, day 2's
     * closing balance stays at 500 instead of the 650 the correct refund would have left
     * it at, and the interest capitalized on day 9 is computed from that permanently
     * short balance. 1.80 AED is credited; 2.22 AED is what the same account would earn
     * with the legitimate correction applied (verified by running the identical scenario
     * with the limit raised to 4, admitting E5) - a 0.42 AED understatement that traces
     * to a scale safeguard, not to anything wrong with the customer's money.
     */
    @Test
    @DisplayName("KNOWN GAP (consequence of the above) - capitalized interest permanently "
            + "understates what the account actually earned")
    void refusedCorrectionLeavesInterestPermanentlyMismatched() {
        StreamRunResult result = Streams.run(legitimateCorrectionChainScenario());

        assertEquals(0, new BigDecimal("500.00").compareTo(
                        result.engine().dailyAccount("ACC001", 2).closingBalance()),
                "day 2 is stuck at 500 - the correct 150 refund (E5) never lands");
        assertEquals(0, new BigDecimal("2.22").compareTo(result.engine().capitalizedInterest("ACC001")),
                "EXPECTED TO FAIL: interest capitalizes at 1.80, not the 2.22 the account "
                        + "would earn with the legitimate refund applied (day 2 at 650, "
                        + "confirmed by running this same scenario with the cycle limit "
                        + "raised to 4) - a permanent, silent 0.42 AED understatement with no "
                        + "error, no flag, and nothing in the report to say interest is short.");
    }

    /**
     * Day 1: credit 1000. Day 2: debit 500 (a subscription charge, native close - cycle
     * 1). Day 5: credit 100 valued day 2 (a refund, posted for the wrong amount - cycle
     * 2). Day 6: reversal of the wrong refund, valued day 2 (cycle 3). Day 8: credit 150
     * valued day 2 (the correct refund - would be cycle 4, refused by the default limit
     * of 3). Capitalization on day 9, after every correction has had its chance to land.
     */
    private EventStream legitimateCorrectionChainScenario() {
        LedgerConfig config = LedgerConfig.builder()
                .weekStartDate(LocalDate.of(2026, 6, 1)).windowDays(9).capitalizationDay(9).build();
        List<LedgerEvent> events = List.of(
                LedgerEvent.builder("E1", 1, "ACC001", EventType.CREDIT).amount("1000").build(),
                LedgerEvent.builder("E2", 2, "ACC001", EventType.DEBIT).amount("500")
                        .note("a subscription charge - day 2's native close, cycle 1").build(),
                LedgerEvent.builder("E3", 5, "ACC001", EventType.CREDIT).amount("100").valueDay(2)
                        .note("a refund, posted for the wrong amount - cycle 2").build(),
                LedgerEvent.builder("E4", 6, "ACC001", EventType.REVERSAL).target("E3").valueDay(2)
                        .note("reversing the wrong refund - cycle 3").build(),
                LedgerEvent.builder("E5", 8, "ACC001", EventType.CREDIT).amount("150").valueDay(2)
                        .note("the correct refund amount - cycle 4, refused by the default "
                                + "limit even though it is exactly right").build());
        return new EventStream("known-gap-legitimate-fourth-correction",
                "A legitimate 4th correction refused for scale, and the interest it leaves mismatched", "",
                config, List.of(new AccountSpec("ACC001", Currency.AED)), events, new Expectations());
    }
}
