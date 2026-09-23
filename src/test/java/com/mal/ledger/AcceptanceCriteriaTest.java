package com.mal.ledger;

import com.mal.ledger.domain.AuthStatus;
import com.mal.ledger.domain.Transaction;
import com.mal.ledger.domain.TransactionType;
import com.mal.ledger.engine.ErrorCode;
import com.mal.ledger.report.StreamRunResult;
import com.mal.ledger.support.Streams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "Acceptance and Rejections" list from Notes.md, one test per criterion, each
 * recording whether the criterion is right or wrong and why. Run on the baseline stream.
 *
 * <p>{@code gradle test --tests '*AcceptanceCriteriaTest'}
 */
@DisplayName("Notes.md acceptance criteria - which are right, which are wrong")
class AcceptanceCriteriaTest {

    private static StreamRunResult result;

    @BeforeAll
    static void runStream() {
        result = Streams.run("iteration-1-baseline");
    }

    @Test
    @DisplayName("WRONG - 'day 2 is -370 so exactly one overdraft fee is assessed'")
    void exactlyOneFeeIsWrong() {
        // The -370 figure is right: that is day 2's balance before its own fee.
        assertEquals(0, new BigDecimal("-370.00").compareTo(
                result.day(5).accounts().get(0).history().get(1).assessmentBalance()));

        // The conclusion is wrong. The fee pushes day 2 to -395, which carries into day 3
        // (+400 leaves only 5.00) and then into day 4, which closes at -180 and is charged
        // again. Two fees, on day 2 and day 4.
        List<Transaction> fees = result.engine().transactionsFor("ACC001").stream()
                .filter(t -> t.type() == TransactionType.OVERDRAFT_FEE).toList();
        assertNotEquals(1, fees.size(), "'exactly one fee' is the part that is wrong");
        assertEquals(2, fees.size());
        assertEquals(List.of("2026-01-02", "2026-01-04"),
                fees.stream().map(t -> t.valueDate().toString()).toList());
    }

    @Test
    @DisplayName("RIGHT - 'the day 4 settlement of authA should be accepted'")
    void authASettlementAccepted() {
        // Both conditions hold at the moment it arrives: the ledger stood at 650 (>= 185)
        // and 185 is within the 200 hold. The back-dated debit that later drags day 4
        // negative had not been instructed yet, and cannot retroactively refuse it.
        assertEquals(AuthStatus.SETTLED, result.engine().auths().get("authA").status());
        assertEquals(0, new BigDecimal("185.00").compareTo(
                result.engine().auths().get("authA").settledAmount()));
        assertTrue(result.engine().transactionsFor("ACC001").stream()
                .anyMatch(t -> t.type() == TransactionType.SETTLEMENT
                        && t.signedAmount().compareTo(new BigDecimal("-185.00")) == 0));
    }

    @Test
    @DisplayName("RIGHT - 'a settlement quoting an unknown auth id must be refused'")
    void unknownAuthRefused() {
        // Accepting it would let a settlement instruction alone move money with no prior
        // authorization - the security hole the notes point at. Refuse and record it.
        assertTrue(result.errors().stream().anyMatch(e ->
                e.eventLabel().equals("E6") && e.code() == ErrorCode.UNKNOWN_AUTH));
        assertFalse(result.engine().auths().containsKey("authZ"));
        assertEquals(0, result.engine().transactionsFor("ACC001").stream()
                        .filter(t -> "authZ".equals(t.reference())).count(),
                "funds must not leave the account");

        // Edge cases covered by the sibling iteration: an auth that exists but was
        // rejected, one already settled, and a settlement larger than its hold.
        StreamRunResult holds = Streams.run("iteration-3-auth-holds");
        assertTrue(holds.errors().stream().anyMatch(e -> e.code() == ErrorCode.AUTH_NOT_APPROVED));
        assertTrue(holds.errors().stream().anyMatch(e -> e.code() == ErrorCode.AUTH_ALREADY_SETTLED));
        assertTrue(holds.errors().stream().anyMatch(e -> e.code() == ErrorCode.SETTLEMENT_EXCEEDS_AUTH));
    }

    @Test
    @DisplayName("RIGHT as a rule, MOOT here - 'holds reduce available balance but not ledger balance'")
    void holdsDoNotTouchLedgerBalance() {
        // The rule is right, and iteration 3 exercises it on an approved hold.
        StreamRunResult holds = Streams.run("iteration-3-auth-holds");
        assertEquals(0, new BigDecimal("500.00").compareTo(
                holds.day(2).accounts().get(0).closingBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(holds.day(2).accounts().get(0).availableBalance()));

        // But it decides nothing in the baseline: authB never becomes a hold at all,
        // because available balance was -205 when the authorization was requested.
        assertEquals(AuthStatus.REJECTED, result.engine().auths().get("authB").status());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.day(5).accounts().get(0).activeHolds()));
    }

    @Test
    @DisplayName("RIGHT - 'after E9 all balances and fees return to pre-E7 values'")
    void reversalRestoresPreE7State() {
        assertEquals(0, new BigDecimal("465.00").compareTo(
                        result.engine().dailyAccount("ACC001", 6).closingBalanceExcludingInterest()),
                "465.00 is exactly where day 4 stood before E7 arrived");
        assertEquals(0, BigDecimal.ZERO.compareTo(result.engine().netOverdraftFees("ACC001")),
                "with the debit gone there is nothing to charge for, so both fees reverse");
        assertEquals(0, new BigDecimal("0.82").compareTo(result.engine().capitalizedInterest("ACC001")),
                "the accrual history is restated too, and capitalizes at the pre-E7 total");
        assertEquals(AuthStatus.REJECTED, result.engine().auths().get("authB").status(),
                "E8's refusal is not undone: it was correct on the information available that day");
    }

    @Test
    @DisplayName("WRONG - 'the three BHD installments should be 3.334'")
    void installmentsAreNotRoundedUp() {
        List<String> slices = result.engine().transactionsFor("ACC002").stream()
                .filter(t -> t.type() == TransactionType.INSTALLMENT_CREDIT)
                .map(t -> t.signedAmount().toPlainString()).toList();
        assertEquals(List.of("3.333", "3.333", "3.333"), slices,
                "3.334 x 3 = 10.002, which credits more than was instructed");

        // The remainder is not lost: it is posted as its own tagged record.
        Transaction buffer = result.engine().transactionsFor("ACC002").stream()
                .filter(t -> t.type() == TransactionType.BALANCING_ADJUSTMENT)
                .findFirst().orElseThrow();
        assertEquals("0.001", buffer.signedAmount().toPlainString());
        assertEquals("BUFFER_PAY", buffer.tag());
        assertEquals(0, new BigDecimal("10.000").compareTo(
                        result.engine().dailyAccount("ACC002", 5).closingBalance()),
                "slices plus buffer reconstitute the exact total");
    }

    @Test
    @DisplayName("WRONG - 'if rounded daily accruals do not sum to the capitalized total, discard the remainder'")
    void accrualRemainderIsNotDiscarded() {
        // The capitalized total is exactly the sum of the stored daily accruals, rounded
        // at store time. Nothing is dropped, so the credit always reconciles to the trail.
        BigDecimal sumOfDailies = BigDecimal.ZERO;
        for (int day = 1; day <= 5; day++) {
            sumOfDailies = sumOfDailies.add(result.engine().dailyAccount("ACC001", day).interestAccrual());
        }
        assertEquals(0, new BigDecimal("0.82").compareTo(sumOfDailies));
        assertEquals(0, sumOfDailies.compareTo(result.engine().capitalizedInterest("ACC001")),
                "the capitalized credit must equal the accrual history it came from");
    }
}
