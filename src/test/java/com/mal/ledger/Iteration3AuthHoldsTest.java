package com.mal.ledger;

import com.mal.ledger.domain.AuthStatus;
import com.mal.ledger.engine.ErrorCode;
import com.mal.ledger.report.StreamRunResult;
import com.mal.ledger.support.StreamAssertions;
import com.mal.ledger.support.Streams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iteration 3 - authorization and hold lifecycle, isolated from back-dating and overdraft.
 *
 * <p>Independently executable: {@code gradle test --tests '*Iteration3AuthHoldsTest'}.
 */
@DisplayName("Iteration 3 - authorization and holds")
class Iteration3AuthHoldsTest {

    private static StreamRunResult result;

    @BeforeAll
    static void runStream() {
        result = Streams.run("iteration-3-auth-holds");
    }

    @Test
    @DisplayName("every figure declared in the stream file holds")
    void matchesDeclaredExpectations() {
        StreamAssertions.verify(result);
    }

    @Test
    @DisplayName("a hold is approved at exactly zero headroom and refused one unit beyond it")
    void approvalBoundary() {
        assertEquals(AuthStatus.REJECTED, auth("authB"), "500 - 200 - 400 = -100");
        assertEquals(AuthStatus.SETTLED, auth("authC"), "500 - 200 - 300 = 0, approved then settled");
        assertEquals(AuthStatus.REJECTED, auth("authD"), "nothing available at all");
        assertEquals(AuthStatus.APPROVED, auth("authE"), "100 - 0 - 100 = 0, approved");
    }

    @Test
    @DisplayName("holds reduce available balance but never the ledger balance")
    void holdsDoNotTouchLedger() {
        assertEquals(0, new BigDecimal("500.00").compareTo(
                result.day(2).accounts().get(0).closingBalance()));
        assertEquals(0, new BigDecimal("500.00").compareTo(
                result.day(2).accounts().get(0).activeHolds()));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                result.day(2).accounts().get(0).availableBalance()));
    }

    @Test
    @DisplayName("a hold left unsettled still suppresses availability at the end of the window")
    void unsettledHoldStillHeld() {
        assertEquals(AuthStatus.APPROVED, auth("authE"));
        assertEquals(0, new BigDecimal("100.00").compareTo(
                result.day(6).accounts().get(0).activeHolds()));
        assertEquals(0, new BigDecimal("0.56").compareTo(
                        result.day(6).accounts().get(0).availableBalance()),
                "only the capitalized interest is free to spend");
    }

    @Test
    @DisplayName("each settlement rejection reports its own distinct reason and moves no money")
    void settlementRejections() {
        assertRejected("E5", ErrorCode.SETTLEMENT_EXCEEDS_AUTH);
        assertRejected("E7", ErrorCode.AUTH_ALREADY_SETTLED);
        assertRejected("E8", ErrorCode.AUTH_NOT_APPROVED);
        assertRejected("E13", ErrorCode.UNKNOWN_AUTH);
        assertEquals(6, result.errors().size());
        assertEquals(5, result.engine().journal().size(),
                "2 credits, 2 settlements and 1 interest credit - the four refusals wrote nothing");
    }

    @Test
    @DisplayName("a zero closing balance accrues nothing but raises no overdraft fee either")
    void zeroBalanceDay() {
        assertEquals(0, BigDecimal.ZERO.compareTo(result.engine().dailyAccount("ACC001", 4).closingBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.engine().dailyAccount("ACC001", 4).interestAccrual()));
        assertEquals(0, result.engine().overdraftFeeRecordCount("ACC001"), "zero is not negative");
    }

    private AuthStatus auth(String id) {
        return result.engine().auths().get(id).status();
    }

    private void assertRejected(String label, ErrorCode code) {
        assertTrue(result.errors().stream().anyMatch(e ->
                        e.eventLabel().equals(label) && e.code() == code),
                label + " should be rejected with " + code + "; got " + result.errors());
    }
}
