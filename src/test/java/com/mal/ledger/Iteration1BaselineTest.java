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
    @DisplayName("days 1-4 close at 250 / 250 / 650 / 465 with holds of 0 / 200 / 200 / 0")
    void firstFourDays() {
        assertClosing(1, "ACC001", "250.00");
        assertClosing(2, "ACC001", "250.00");
        assertClosing(3, "ACC001", "650.00");
        assertClosing(4, "ACC001", "465.00");

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
        assertEquals(0, new BigDecimal("250.00").compareTo(day2.closingBalance()),
                "after day 6 reverses E7, day 2 is back to 250");
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
    @DisplayName("after E9 every balance and fee returns to its pre-E7 value")
    void reversalRestoresEverything() {
        assertEquals(0, new BigDecimal("465.00").compareTo(
                        result.engine().dailyAccount("ACC001", 6).closingBalanceExcludingInterest()),
                "day 6 closes at 465 before interest, exactly the pre-E7 day-4 figure carried forward");
        assertEquals(0, BigDecimal.ZERO.compareTo(result.engine().netOverdraftFees("ACC001")),
                "both fees must be reversed - with the debit gone there is nothing to charge for");
        assertEquals(2, result.engine().transactionsFor("ACC001").stream()
                        .filter(t -> t.type() == TransactionType.OVERDRAFT_FEE_REVERSAL).count(),
                "reversed by new records, never by deleting the fees");
    }

    @Test
    @DisplayName("interest capitalizes once, at 0.82 AED, from the restated accrual history")
    void interestCapitalization() {
        assertEquals(0, new BigDecimal("0.82").compareTo(result.engine().capitalizedInterest("ACC001")));
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
