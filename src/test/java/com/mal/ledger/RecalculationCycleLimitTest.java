package com.mal.ledger;

import com.mal.ledger.domain.Currency;
import com.mal.ledger.engine.ErrorCode;
import com.mal.ledger.engine.LedgerConfig;
import com.mal.ledger.engine.RecalculationCycle;
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
 * The scenario given for the recalculation cycle limit: day 2 is calculated natively
 * (cycle 1), a day-5 entry lands on it (cycle 2), a day-7 reversal lands on it (cycle 3),
 * and a day-8 debit would be a 4th cycle - which the default max of 3 refuses outright,
 * before anything is appended.
 *
 * <p>Independently executable: {@code mvn test -Dtest=RecalculationCycleLimitTest}
 */
@DisplayName("Recalculation cycle limit")
class RecalculationCycleLimitTest {

    @Test
    @DisplayName("day 2's 4th cycle is refused upfront - no transaction, no funds move")
    void fourthCycleIsRefused() {
        StreamRunResult result = Streams.run(cycleScenario(LedgerConfig.builder()
                .weekStartDate(LocalDate.of(2026, 4, 1)).windowDays(9).capitalizationDay(9).build()));

        LocalDate day2 = result.stream().config().dateOfDay(2);

        // Native close (day 2 itself) = cycle 1, never logged. E3 (day 5, credit valued
        // day 2) = cycle 2. E4 (day 7, reversal valued day 2) = cycle 3. Both admitted.
        assertEquals(3, result.engine().recalculationCycleCount("ACC001", day2));
        List<RecalculationCycle> cycles = result.engine().recalculationCycles();
        assertEquals(2, cycles.size(), "only the two admitted back-dated writes are logged: " + cycles);
        assertEquals(2, cycles.get(0).cycleNumber());
        assertEquals(3, cycles.get(1).cycleNumber());
        assertEquals("ACC001", cycles.get(0).accountId());
        assertEquals(day2, cycles.get(0).valueDate());

        // E5 (day 8, debit valued day 2) would be a 4th cycle - refused before any
        // transaction is created for it, with the dedicated error code.
        assertTrue(result.errors().stream().anyMatch(e ->
                        e.eventLabel().equals("E5") && e.code() == ErrorCode.RECALCULATION_LIMIT_EXCEEDED),
                "E5 should be refused for exceeding the recalculation cycle limit: " + result.errors());
        assertTrue(result.engine().transactionsFor("ACC001").stream()
                        .noneMatch(t -> "E5".equals(t.sourceLabel())),
                "a refused write must leave no ledger record behind - funds must not move");
        assertEquals(3, result.engine().recalculationCycleCount("ACC001", day2),
                "a refused write does not consume a cycle slot - the count stays at 3");

        // day 2 nets back to its original 900 (the day-5 credit and day-7 reversal of it
        // cancel out); the refused day-8 debit leaves it there rather than at 850.
        assertEquals(0, new BigDecimal("900.00").compareTo(
                result.engine().dailyAccount("ACC001", 8).closingBalance()));
    }

    @Test
    @DisplayName("raising the limit lets the same 4th cycle through")
    void higherLimitAdmitsTheFourthCycle() {
        StreamRunResult result = Streams.run(cycleScenario(LedgerConfig.builder()
                .weekStartDate(LocalDate.of(2026, 4, 1)).windowDays(9).capitalizationDay(9)
                .maxRecalculationCycles(4)
                .build()));

        LocalDate day2 = result.stream().config().dateOfDay(2);
        assertEquals(4, result.engine().recalculationCycleCount("ACC001", day2));
        assertTrue(result.errors().stream().noneMatch(e -> e.eventLabel().equals("E5")),
                "with the limit raised to 4, E5 is admitted, not refused");
        assertEquals(0, new BigDecimal("850.00").compareTo(
                        result.engine().dailyAccount("ACC001", 8).closingBalance()),
                "900 less the day-8 debit of 50, once it is allowed through");
    }

    /**
     * E1 credit(d1) -> native.
     * E2 debit 100(d2) -> native close of day 2, cycle 1 (not logged - nothing has
     * recalculated yet).
     * E3 credit 500(d5, valued d2) -> cycle 2.
     * E4 reversal of E3(d7, valued d2) -> cycle 3.
     * E5 debit 50(d8, valued d2) -> would be cycle 4.
     */
    private EventStream cycleScenario(LedgerConfig config) {
        List<LedgerEvent> events = List.of(
                LedgerEvent.builder("E1", 1, "ACC001", EventType.CREDIT).amount("1000").build(),
                LedgerEvent.builder("E2", 2, "ACC001", EventType.DEBIT).amount("100").build(),
                LedgerEvent.builder("E3", 5, "ACC001", EventType.CREDIT).amount("500").valueDay(2)
                        .note("day 2's 2nd cycle").build(),
                LedgerEvent.builder("E4", 7, "ACC001", EventType.REVERSAL).target("E3").valueDay(2)
                        .note("day 2's 3rd cycle").build(),
                LedgerEvent.builder("E5", 8, "ACC001", EventType.DEBIT).amount("50").valueDay(2)
                        .note("day 2's 4th cycle - refused by the default limit of 3").build());
        return new EventStream("cycle-limit-scenario", "Recalculation cycle limit probe", "",
                config, List.of(new AccountSpec("ACC001", Currency.AED)), events, new Expectations());
    }
}
