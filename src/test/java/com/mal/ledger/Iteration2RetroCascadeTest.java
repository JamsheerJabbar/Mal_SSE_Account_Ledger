package com.mal.ledger;

import com.mal.ledger.domain.Transaction;
import com.mal.ledger.domain.TransactionType;
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
 * Iteration 2 - a huge back-dated debit lands on day 7, after interest has already been
 * credited, and is reversed on day 8. This is the "everything can change if on day 7 a
 * huge reversal occurs" case from Notes.md.
 *
 * <p>Independently executable: {@code gradle test --tests '*Iteration2RetroCascadeTest'}.
 */
@DisplayName("Iteration 2 - day-7 reversal cascade")
class Iteration2RetroCascadeTest {

    private static StreamRunResult result;

    @BeforeAll
    static void runStream() {
        result = Streams.run("iteration-2-retro-cascade");
    }

    @Test
    @DisplayName("every figure declared in the stream file holds")
    void matchesDeclaredExpectations() {
        StreamAssertions.verify(result);
    }

    @Test
    @DisplayName("day 6 capitalizes 1.92 on a clean week")
    void capitalizesOnDaySix() {
        assertEquals(0, new BigDecimal("1.92").compareTo(
                result.day(6).accounts().get(0).capitalizedInterest()));
        assertEquals(0, new BigDecimal("901.92").compareTo(
                result.day(6).accounts().get(0).closingBalance()));
    }

    @Test
    @DisplayName("the day-7 debit drags days 3-6 overdrawn, raising four fees in one pass")
    void cascadeRaisesFourFees() {
        List<Transaction> feesOnDay7 = result.day(7).systemGenerated().stream()
                .filter(t -> t.type() == TransactionType.OVERDRAFT_FEE)
                .toList();
        assertEquals(4, feesOnDay7.size(), "days 3, 4, 5 and 6 each close negative");
        assertEquals(0, new BigDecimal("-1199.32").compareTo(
                result.day(7).accounts().get(0).closingBalance()));
    }

    @Test
    @DisplayName("interest is not patched - the old credit is reversed in full and a new one posted")
    void interestIsReversedNotPatched() {
        List<Transaction> onDay7 = result.day(7).systemGenerated().stream()
                .filter(t -> t.type().isInterestRecord())
                .toList();
        assertEquals(2, onDay7.size(), "one reversal plus one fresh capitalization");
        assertEquals(TransactionType.INTEREST_CAPITALIZATION_REVERSAL, onDay7.get(0).type());
        assertEquals("-1.92", onDay7.get(0).signedAmount().toPlainString());
        assertEquals(TransactionType.INTEREST_CAPITALIZATION, onDay7.get(1).type());
        assertEquals("0.68", onDay7.get(1).signedAmount().toPlainString(),
                "days 3-5 no longer accrue, so only days 1-2 survive");
    }

    @Test
    @DisplayName("day 8 undoes the debit itself, but not the four permanent fees it caused")
    void dayEightSettlesShortOfThePreDebitPosition() {
        assertEquals(0, new BigDecimal("801.86").compareTo(
                        result.day(8).accounts().get(0).closingBalance()),
                "100 AED (four fees) below the day-6 closing balance, not identical to it");
        assertEquals(0, new BigDecimal("-100.00").compareTo(result.engine().netOverdraftFees("ACC001")),
                "none of the four fees reverse - they are permanent history (REJECTED.md R2)");
        assertEquals(0, new BigDecimal("1.86").compareTo(result.engine().capitalizedInterest("ACC001")),
                "interest restates from the permanently-lower balances, not back to 1.92");
    }

    @Test
    @DisplayName("the restatement takes more than one recompute pass and still reaches a fixed point")
    void recomputeLoopsAndConverges() {
        assertTrue(result.day(7).recomputePasses() >= 2,
                "fees change the balance, which changes the accrual, which changes the interest credit");
        assertTrue(result.day(8).recomputePasses() >= 2);
        assertTrue(result.days().stream().allMatch(d -> d.converged()),
                "every day must settle - no cyclic recalculation");
    }

    @Test
    @DisplayName("nothing is ever rewritten: 20 records, every correction appended, no fee ever reversed")
    void appendOnly() {
        assertEquals(16, result.engine().journal().size(),
                "4 fewer than under fee-reversal: no OVERDRAFT_FEE_REVERSAL records are ever created");
        assertEquals(4, result.engine().transactionsFor("ACC001").stream()
                .filter(t -> t.type() == TransactionType.OVERDRAFT_FEE).count());
        assertTrue(result.engine().transactionsFor("ACC001").stream()
                        .noneMatch(t -> "OVERDRAFT_FEE_REVERSAL".equals(t.type().name())),
                "the type does not exist any more - a fee, once charged, is never undone");
        assertEquals(0, result.errors().size());
    }
}
