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
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iteration 1 - the stream written out in Notes.md, run on its own.
 *
 * <p>Independently executable: {@code gradle test --tests '*Iteration1BaselineTest'}.
 */
@DisplayName("Iteration 1 - Notes.md baseline")
class Iteration1BaselineTest {

    private static final String ID = "iteration-1-baseline";
    private static StreamRunResult result;

    @BeforeAll
    static void runStream() {
        result = Streams.run(ID);
    }

    @Test
    @DisplayName("every figure declared in the stream file holds")
    void matchesDeclaredExpectations() {
        StreamAssertions.verify(result);
    }

    @Test
    @DisplayName("final closing settles at 250 / 225 / 625 / 415, holds of 0 / 200 / 200 / 0 at the time")
    void firstFourDays() {
        // assertClosing reads the FINAL (end-of-window) balance for each day, not the
        // figure that day's own report first showed. Days 2 and 4 never recover their
        // original 250/465: the day-2 and day-4 overdraft fees raised on day 5 are
        // permanent (see REJECTED.md R2), so they still cost 25 AED apiece here, even
        // though E9 later reverses the debit that caused them.
        assertClosing(1, "ACC001", "250.00");
        assertClosing(2, "ACC001", "225.00");
        assertClosing(3, "ACC001", "625.00");
        assertClosing(4, "ACC001", "415.00");

        // Holds, by contrast, are read from each day's OWN report, captured at the moment
        // it was built - unaffected by anything that happens later.
        assertEquals(0, new BigDecimal("0.00").compareTo(holds(1)));
        assertEquals(0, new BigDecimal("200.00").compareTo(holds(2)));
        assertEquals(0, new BigDecimal("200.00").compareTo(holds(3)));
        assertEquals(0, new BigDecimal("0.00").compareTo(holds(4)),
                "authA settled on day 4, so the hold is released that day");
    }

    @Test
    @DisplayName("the day-5 back-dated debit restates day 2 to -370 before any fee is assessed")
    void backdatedDebitRestatesDayTwo() {
        var day2 = result.engine().dailyAccount("ACC001", 2);
        assertEquals(0, new BigDecimal("-370.00").compareTo(
                        result.day(5).accounts().get(0).history().get(1).assessmentBalance()),
                "day 2 is judged on its balance before its own fee");
        assertTrue(result.day(5).restatedHistory(), "day 5 must be flagged as restating history");
        assertEquals(0, new BigDecimal("225.00").compareTo(day2.closingBalance()),
                "day 6 reverses E7 itself, netting day 2's own ledger movement back to 250 - "
                        + "but the -25 fee E7 caused on day 2 is never reversed, so day 2 settles "
                        + "at 225, not the original 250");
    }

    @Test
    @DisplayName("two overdraft fees are assessed - on day 2 and day 4, not just one")
    void twoOverdraftFeesNotOne() {
        List<Transaction> fees = result.engine().transactionsFor("ACC001").stream()
                .filter(t -> t.type() == TransactionType.OVERDRAFT_FEE)
                .toList();
        assertEquals(2, fees.size(),
                "the notes' 'exactly one overdraft fee' criterion is wrong: day 4 closes at -180 too");
        assertEquals(List.of(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 4)),
                fees.stream().map(Transaction::valueDate).toList(),
                "each fee carries the value date of the day it punishes");
    }

    @Test
    @DisplayName("settlement against an auth the ledger never saw is refused and no funds move")
    void unknownAuthSettlementRefused() {
        assertTrue(result.errors().stream().anyMatch(e ->
                        e.eventLabel().equals("E6") && e.code() == ErrorCode.UNKNOWN_AUTH),
                "E6 quotes authZ, which was never raised");
        assertFalse(result.engine().transactionsFor("ACC001").stream()
                        .anyMatch(t -> "authZ".equals(t.reference())),
                "no ledger record may exist for the refused settlement");
    }

    @Test
    @DisplayName("authB is refused: a hold cannot be approved against a negative available balance")
    void authBRefused() {
        assertEquals(AuthStatus.REJECTED, result.engine().auths().get("authB").status());
        assertEquals(0, new BigDecimal("-205.00").compareTo(
                result.day(5).accounts().get(0).availableBalance()));
    }

    @Test
    @DisplayName("after E9 the debit's own effect is undone, but neither fee it caused reverses")
    void reversalDoesNotUndoTheFeesItCaused() {
        assertEquals(0, new BigDecimal("415.00").compareTo(
                        result.engine().dailyAccount("ACC001", 6).closingBalanceExcludingInterest()),
                "465 minus the two 25 AED fees that never reverse, not the original 465");
        assertEquals(0, new BigDecimal("-50.00").compareTo(result.engine().netOverdraftFees("ACC001")),
                "both fees stand - E9 undoes E7's debit, not the fees E7 caused along the way");
        assertEquals(2, result.engine().transactionsFor("ACC001").stream()
                        .filter(t -> t.type() == TransactionType.OVERDRAFT_FEE).count(),
                "exactly the two fees originally charged - no reversal record of any kind exists");
        assertTrue(result.engine().transactionsFor("ACC001").stream()
                        .noneMatch(t -> "OVERDRAFT_FEE_REVERSAL".equals(t.type().name())),
                "the type does not exist any more - once charged, a fee is permanent history");
    }

    @Test
    @DisplayName("interest capitalizes once, at 0.76 AED, from the restated (permanently fee-reduced) history")
    void interestCapitalization() {
        assertEquals(0, new BigDecimal("0.76").compareTo(result.engine().capitalizedInterest("ACC001")));
        assertEquals(1, result.engine().transactionsFor("ACC001").stream()
                .filter(t -> t.type() == TransactionType.INTEREST_CAPITALIZATION).count());
        assertEquals(0, new BigDecimal("0.004").compareTo(result.engine().capitalizedInterest("ACC002")));
    }

    @Test
    @DisplayName("10 BHD in 3 installments posts 3.333 x3 plus a 0.001 buffer pay, never 3.334")
    void bhdInstallmentSplit() {
        List<BigDecimal> installments = result.engine().transactionsFor("ACC002").stream()
                .filter(t -> t.type() == TransactionType.INSTALLMENT_CREDIT)
                .map(Transaction::signedAmount).toList();
        assertEquals(3, installments.size());
        for (BigDecimal slice : installments) {
            assertEquals("3.333", slice.toPlainString(), "each slice rounds down, never up to 3.334");
        }
        List<BigDecimal> buffer = result.engine().transactionsFor("ACC002").stream()
                .filter(t -> t.type() == TransactionType.BALANCING_ADJUSTMENT)
                .map(Transaction::signedAmount).toList();
        assertEquals(List.of(new BigDecimal("0.001")), buffer, "the remainder is posted, not dropped");
        assertEquals("BUFFER_PAY", result.engine().transactionsFor("ACC002").stream()
                .filter(t -> t.type() == TransactionType.BALANCING_ADJUSTMENT)
                .findFirst().orElseThrow().tag());
    }

    private void assertClosing(int day, String accountId, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(
                        result.engine().dailyAccount(accountId, day).closingBalance()),
                "day " + day + " closing balance");
    }

    private BigDecimal holds(int day) {
        return result.day(day).accounts().get(0).activeHolds();
    }
}
