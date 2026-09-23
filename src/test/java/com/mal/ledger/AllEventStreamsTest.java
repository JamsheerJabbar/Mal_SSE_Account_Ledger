package com.mal.ledger;

import com.mal.ledger.domain.Transaction;
import com.mal.ledger.events.EventStream;
import com.mal.ledger.report.StreamRunResult;
import com.mal.ledger.support.StreamAssertions;
import com.mal.ledger.support.Streams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes every stream file in the event-stream directory, one test per file.
 *
 * <p>This is the iterative suite: it discovers the files at run time, so editing a
 * stream changes what runs, and dropping a new {@code .json} file into the directory
 * adds a new test case with no Java change at all.
 */
@DisplayName("All event streams")
class AllEventStreamsTest {

    static Stream<EventStream> streams() {
        return Streams.all().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("stream runs and meets the expectations declared in its own file")
    void runsToExpectation(EventStream stream) {
        StreamRunResult result = Streams.run(stream);
        StreamAssertions.verify(result);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("stream holds the ledger invariants regardless of what it declares")
    void holdsLedgerInvariants(EventStream stream) {
        StreamRunResult result = Streams.run(stream);

        assertTrue(result.days().stream().allMatch(d -> d.converged()),
                "every day must reach a fixed point");

        for (String accountId : result.engine().accounts().keySet()) {
            int precision = result.engine().account(accountId).precision();
            BigDecimal sum = BigDecimal.ZERO;
            for (Transaction t : result.engine().transactionsFor(accountId)) {
                assertEquals(precision, t.signedAmount().scale(),
                        accountId + " " + t.id() + " must be stored at the account precision");
                assertFalse(t.valueDate().isBefore(stream.config().weekStartDate()),
                        accountId + " " + t.id() + " has a value date before the week start");
                sum = sum.add(t.signedAmount());
            }
            assertEquals(0, sum.compareTo(result.engine().account(accountId).closingLedgerAmount()),
                    accountId + ": the derived daily accounts must reconcile to the journal");
        }

        List<String> ids = result.engine().journal().stream().map(Transaction::id).toList();
        assertEquals(ids.size(), ids.stream().distinct().count(),
                "record ids must be unique - the journal is append only");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("stream is fully reproducible - two runs produce identical journals")
    void isDeterministic(EventStream stream) {
        List<String> first = Streams.run(stream).engine().journal().stream()
                .map(Transaction::toString).toList();
        List<String> second = Streams.run(stream).engine().journal().stream()
                .map(Transaction::toString).toList();
        assertEquals(first, second);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("stream prints a day report for every day in its window")
    void printsADayReport(EventStream stream) {
        StreamRunResult result = Streams.run(stream);
        String report = Streams.report(result);
        for (int day = 1; day <= stream.lastProcessedDay(); day++) {
            assertTrue(report.contains("### DAY " + day + " "), "report must cover day " + day);
        }
        assertTrue(report.contains("closing ledger balance"));
        assertTrue(report.contains("auth states"));
        assertTrue(report.contains("errors"));
    }

    @Test
    @DisplayName("the directory holds the five independent iterations")
    void theFiveIterations() {
        List<String> ids = Streams.all().stream().map(EventStream::id).toList();
        assertTrue(ids.containsAll(List.of(
                        "iteration-1-baseline",
                        "iteration-2-retro-cascade",
                        "iteration-3-auth-holds",
                        "iteration-4-precision",
                        "iteration-5-mixed-stress")),
                "expected the five shipped iterations, found " + ids);
    }

    @Test
    @DisplayName("every shipped iteration uses its own accounts and its own instructions")
    void iterationsAreIndependent() {
        for (EventStream a : Streams.all()) {
            for (EventStream b : Streams.all()) {
                if (a.id().equals(b.id())) continue;
                assertFalse(a.events() == b.events(), a.id() + " and " + b.id() + " share event state");
            }
            assertTrue(a.accounts().size() > 0, a.id() + " opens no accounts");
            assertTrue(a.events().size() > 0, a.id() + " has no instructions");
        }
    }
}
