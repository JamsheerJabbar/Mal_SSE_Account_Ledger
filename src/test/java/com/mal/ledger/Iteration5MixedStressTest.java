package com.mal.ledger;

import com.mal.ledger.domain.AuthStatus;
import com.mal.ledger.domain.Transaction;
import com.mal.ledger.domain.TransactionType;
import com.mal.ledger.engine.ErrorCode;
import com.mal.ledger.report.StreamRunResult;
import com.mal.ledger.support.StreamAssertions;
import com.mal.ledger.support.Streams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iteration 5 - two currencies side by side, a hold live across a restatement, and a
 * permanent fee that outlives the debit that caused it - costing a later hold its approval.
 *
 * <p>Independently executable: {@code gradle test --tests '*Iteration5MixedStressTest'}.
 */
@DisplayName("Iteration 5 - mixed currency stress")
class Iteration5MixedStressTest {

    private static StreamRunResult result;

    @BeforeAll
    static void runStream() {
        result = Streams.run("iteration-5-mixed-stress");
    }

    @Test
    @DisplayName("every figure declared in the stream file holds")
    void matchesDeclaredExpectations() {
        StreamAssertions.verify(result);
    }

    @Test
    @DisplayName("the two accounts are assessed independently, each in its own currency")
    void accountsAreIndependent() {
        assertEquals(0, new BigDecimal("-25.00").compareTo(result.engine().netOverdraftFees("ACC001")),
                "the AED fee stands even though its cause (the day-1 debit) was reversed");
        assertEquals(0, new BigDecimal("-2.500").compareTo(result.engine().netOverdraftFees("ACC002")),
                "the BHD fee stands too - day 2 is still overdrawn on its own merits");
        assertEquals(1, result.engine().overdraftFeeRecordCount("ACC001"));
        assertEquals(1, result.engine().overdraftFeeRecordCount("ACC002"));
    }

    @Test
    @DisplayName("a debit back-dated into day 1 raises a fee only on day 1, not on the days it carries into")
    void feeLandsOnTheDayThatCausedIt() {
        Transaction fee = result.engine().transactionsFor("ACC001").stream()
                .filter(t -> t.type() == TransactionType.OVERDRAFT_FEE)
                .findFirst().orElseThrow();
        assertEquals(result.stream().config().dateOfDay(1), fee.valueDate(),
                "the fee carries the value date of the day it punishes, not the day it was calculated");
        assertEquals(result.stream().config().dateOfDay(3), fee.postedDate(),
                "but it was posted on day 3, when the back-dated debit arrived");
    }

    @Test
    @DisplayName("a live hold keeps suppressing availability while history is being restated")
    void holdSurvivesRestatement() {
        var day3 = result.day(3).accounts().get(0);
        assertEquals(0, new BigDecimal("-125.00").compareTo(day3.closingBalance()));
        assertEquals(0, new BigDecimal("300.00").compareTo(day3.activeHolds()),
                "authA is still open on day 3");
        assertEquals(0, new BigDecimal("-425.00").compareTo(day3.availableBalance()),
                "the hold stacks on top of the overdrawn ledger");

        var day4 = result.day(4).accounts().get(0);
        assertEquals(0, new BigDecimal("475.00").compareTo(day4.closingBalance()),
                "800 - 300 settlement - the permanent day-1 fee (25) that E7 does not undo");
        assertEquals(0, BigDecimal.ZERO.compareTo(day4.activeHolds()), "authA settled on day 4");
    }

    @Test
    @DisplayName("a settlement is judged against the ledger as it stands when it arrives")
    void settlementUsesTheLedgerAtTheTime() {
        assertEquals(AuthStatus.SETTLED, result.engine().auths().get("authA").status());
        assertEquals(0, new BigDecimal("300.00").compareTo(
                result.engine().auths().get("authA").settledAmount()));
        assertTrue(result.errors().stream().anyMatch(e ->
                e.eventLabel().equals("E9") && e.code() == ErrorCode.UNKNOWN_AUTH));
        assertTrue(result.errors().stream().anyMatch(e ->
                e.eventLabel().equals("E10") && e.code() == ErrorCode.INSUFFICIENT_AVAILABLE_BALANCE));
    }

    @Test
    @DisplayName("an installment split that divides exactly raises no buffer pay")
    void exactSplitHasNoRemainder() {
        List<String> slices = result.engine().transactionsFor("ACC002").stream()
                .filter(t -> t.type() == TransactionType.INSTALLMENT_CREDIT)
                .map(t -> t.signedAmount().toPlainString()).toList();
        assertEquals(List.of("1.750", "1.750", "1.750", "1.750"), slices);
        assertEquals(0, result.engine().transactionsFor("ACC002").stream()
                        .filter(t -> t.type() == TransactionType.BALANCING_ADJUSTMENT).count(),
                "7.000 / 4 is exact, so there is nothing left to balance");
    }

    @Test
    @DisplayName("each account capitalizes its own interest, in its own precision")
    void interestPerAccount() {
        assertEquals(0, new BigDecimal("1.31").compareTo(result.engine().capitalizedInterest("ACC001")));
        assertEquals(0, new BigDecimal("0.035").compareTo(result.engine().capitalizedInterest("ACC002")));
    }

    @Test
    @DisplayName("a permanent fee can cost a later hold: authC is refused, not approved")
    void permanentFeeCostsALaterHold() {
        // Day 1's fee never reverses, so by day 5 the running balance is 475, not the
        // clean 500 a fully-reversed fee would have left. authC's 500 AED hold request,
        // which would have cleared at exactly zero headroom against 500, is refused
        // against 475 instead.
        assertEquals(AuthStatus.REJECTED, result.engine().auths().get("authC").status());
        assertTrue(result.errors().stream().anyMatch(e ->
                e.eventLabel().equals("E11") && e.code() == ErrorCode.INSUFFICIENT_AVAILABLE_BALANCE));
        assertEquals(0, new BigDecimal("475.00").compareTo(
                result.day(5).accounts().get(0).availableBalance()));
    }
}
