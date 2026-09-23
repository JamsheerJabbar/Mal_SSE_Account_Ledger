package com.mal.ledger;

import com.mal.ledger.domain.Currency;
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
 * Iteration 4 - currency precision, installment remainders and the rounding choice that
 * can nullify interest entirely.
 *
 * <p>Independently executable: {@code gradle test --tests '*Iteration4PrecisionTest'}.
 */
@DisplayName("Iteration 4 - precision and rounding")
class Iteration4PrecisionTest {

    private static StreamRunResult result;

    @BeforeAll
    static void runStream() {
        result = Streams.run("iteration-4-precision");
    }

    @Test
    @DisplayName("every figure declared in the stream file holds")
    void matchesDeclaredExpectations() {
        StreamAssertions.verify(result);
    }

    @Test
    @DisplayName("precision travels with the account: BHD stores 3 dp, AED stores 2 dp")
    void precisionIsPerAccount() {
        assertEquals(Currency.BHD, result.engine().account("BHD001").currency());
        assertEquals(Currency.AED, result.engine().account("AED003").currency());
        for (Transaction t : result.engine().transactionsFor("BHD001")) {
            assertEquals(3, t.signedAmount().scale(), t.id() + " must be stored at 3 dp");
        }
        for (Transaction t : result.engine().transactionsFor("AED003")) {
            assertEquals(2, t.signedAmount().scale(), t.id() + " must be stored at 2 dp");
        }
    }

    @Test
    @DisplayName("installment remainders are posted, never discarded, and always reconstitute the total")
    void installmentRemaindersReconstituteTheTotal() {
        assertSplit(1, "10.000", List.of("3.333", "3.333", "3.333"), "0.001");
        assertSplit(3, "1.000", List.of("0.142", "0.142", "0.142", "0.142", "0.142", "0.142", "0.142"), "0.006");
    }

    @Test
    @DisplayName("rounding down nullifies interest on a small balance - and that is what is capitalized")
    void roundingDownNullifiesInterest() {
        // 10.00 AED x 0.0004 = 0.004 a day: below half a fil, so every day rounds to zero.
        for (int day = 1; day <= 5; day++) {
            assertEquals(0, BigDecimal.ZERO.compareTo(
                            result.engine().dailyAccount("AED003", day).interestAccrual()),
                    "day " + day + " accrual must round to zero");
            assertTrue(result.engine().dailyAccount("AED003", day).rawInterestAccrual().signum() > 0,
                    "day " + day + " did earn interest before rounding - that is the ambiguity");
        }
        assertEquals(0, BigDecimal.ZERO.compareTo(result.engine().capitalizedInterest("AED003")),
                "five days of real accrual capitalize to nothing");
        assertEquals(0, result.engine().transactionsFor("AED003").stream()
                        .filter(t -> t.type().isInterestRecord()).count(),
                "a zero capitalization raises no record at all");

        // 1.000 BHD x 0.0004 = 0.0004 a day: the same nullification one precision deeper.
        for (int day = 1; day <= 3; day++) {
            assertEquals(0, BigDecimal.ZERO.compareTo(
                    result.engine().dailyAccount("BHD002", day).interestAccrual()));
        }
    }

    @Test
    @DisplayName("a day that closes negative keeps its fee even after a later day recovers")
    void feeSurvivesLaterRecovery() {
        assertEquals(0, new BigDecimal("-6.500").compareTo(
                        result.engine().dailyAccount("BHD002", 4).closingBalance()),
                "day 4: -4.000 plus the 2.500 BHD fee");
        assertTrue(result.engine().dailyAccount("BHD002", 4).overdraftEnabled());
        assertEquals(0, new BigDecimal("3.500").compareTo(
                        result.engine().dailyAccount("BHD002", 5).closingBalance()),
                "day 5 recovers on its own credit");
        assertEquals(0, result.engine().dailyAccount("BHD002", 5).overdraftEnabled() ? 1 : 0,
                "day 5 closes positive, so it carries no fee of its own");
        assertEquals(1, result.engine().overdraftFeeRecordCount("BHD002"),
                "day 4 is still overdrawn on its own merits - its fee must not be reversed");
    }

    @Test
    @DisplayName("the BHD overdraft fee is charged in BHD at BHD precision")
    void feeIsChargedInAccountCurrency() {
        Transaction fee = result.engine().transactionsFor("BHD002").stream()
                .filter(t -> t.type() == TransactionType.OVERDRAFT_FEE)
                .findFirst().orElseThrow();
        assertEquals("-2.500", fee.signedAmount().toPlainString());
    }

    private void assertSplit(int day, String total, List<String> slices, String remainder) {
        List<Transaction> onDay = result.engine().transactionsFor("BHD001").stream()
                .filter(t -> t.valueDate().equals(result.stream().config().dateOfDay(day)))
                .toList();
        List<String> actualSlices = onDay.stream()
                .filter(t -> t.type() == TransactionType.INSTALLMENT_CREDIT)
                .map(t -> t.signedAmount().toPlainString()).toList();
        assertEquals(slices, actualSlices, "day " + day + " installments");

        List<String> buffer = onDay.stream()
                .filter(t -> t.type() == TransactionType.BALANCING_ADJUSTMENT)
                .map(t -> t.signedAmount().toPlainString()).toList();
        assertEquals(List.of(remainder), buffer, "day " + day + " buffer pay");

        BigDecimal sum = onDay.stream()
                .filter(t -> !t.isSystemGenerated())
                .map(Transaction::signedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal(total).compareTo(sum),
                "slices plus buffer must add back up to " + total);
        for (String slice : actualSlices) {
            assertTrue(new BigDecimal(slice).multiply(BigDecimal.valueOf(actualSlices.size()))
                            .compareTo(new BigDecimal(total)) <= 0,
                    "every slice must be <= total / n, never rounded up past it");
        }
    }
}
