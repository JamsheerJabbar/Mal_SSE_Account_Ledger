package com.mal.ledger.support;

import com.mal.ledger.domain.Auth;
import com.mal.ledger.domain.DailyAccount;
import com.mal.ledger.domain.Transaction;
import com.mal.ledger.engine.EngineError;
import com.mal.ledger.engine.LedgerEngine;
import com.mal.ledger.events.EventStream;
import com.mal.ledger.events.Expectations;
import com.mal.ledger.report.AccountDayView;
import com.mal.ledger.report.DayReport;
import com.mal.ledger.report.StreamRunResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Turns the expectations carried in a stream file into assertions. Every mismatch is
 * collected, so one run reports every broken figure at once instead of stopping at the
 * first - which is what you want when you have just edited a stream and want to know
 * everything it moved.
 */
public final class StreamAssertions {

    private StreamAssertions() {
    }

    public static void verify(StreamRunResult result) {
        List<String> failures = collect(result);
        if (!failures.isEmpty()) {
            fail("%s: %d expectation(s) failed%n  %s".formatted(
                    result.stream().id(), failures.size(), String.join("\n  ", failures)));
        }
    }

    public static List<String> collect(StreamRunResult result) {
        EventStream stream = result.stream();
        Expectations x = stream.expectations();
        LedgerEngine engine = result.engine();
        List<String> failures = new ArrayList<>();

        verifySnapshots(result, x, failures);
        verifyFinalInterest(engine, x, failures);
        verifyAuthStatus(engine, x, failures);
        verifyOverdraft(engine, x, failures);
        verifyErrors(engine, x, failures);
        verifyTransactions(engine, stream, x, failures);
        verifyRecomputePasses(result, x, failures);
        verifyConvergence(result, failures);
        verifyAppendOnlyInvariants(result, failures);

        return failures;
    }

    // ------------------------------------------------------------ snapshots

    private static void verifySnapshots(StreamRunResult result, Expectations x, List<String> failures) {
        for (Expectations.Snapshot snapshot : x.snapshots()) {
            DayReport report;
            try {
                report = result.day(snapshot.afterDay());
            } catch (IllegalArgumentException e) {
                failures.add("snapshot afterDay=%d: the stream never reached that day"
                        .formatted(snapshot.afterDay()));
                continue;
            }
            AccountDayView view = report.accounts().stream()
                    .filter(v -> v.accountId().equals(snapshot.accountId()))
                    .findFirst().orElse(null);
            if (view == null) {
                failures.add("snapshot afterDay=%d: no account %s".formatted(
                        snapshot.afterDay(), snapshot.accountId()));
                continue;
            }
            for (Expectations.Row expected : snapshot.rows()) {
                AccountDayView.LedgerRow actual = view.history().stream()
                        .filter(r -> r.day() == expected.day())
                        .findFirst().orElse(null);
                if (actual == null) {
                    failures.add("afterDay=%d %s: no ledger row for day %d".formatted(
                            snapshot.afterDay(), snapshot.accountId(), expected.day()));
                    continue;
                }
                String where = "afterDay=%d %s day=%d".formatted(
                        snapshot.afterDay(), snapshot.accountId(), expected.day());
                compare(failures, where, "closing", expected.closing(), actual.closing());
                compare(failures, where, "available", expected.available(), actual.available());
                compare(failures, where, "holds", expected.holds(), actual.holds());
                compare(failures, where, "accrual", expected.accrual(), actual.accrual());
                compare(failures, where, "assessmentBalance",
                        expected.assessmentBalance(), actual.assessmentBalance());
                if (expected.overdraft() != null && expected.overdraft() != actual.overdraftFeeActive()) {
                    failures.add("%s overdraft: expected %s but was %s".formatted(
                            where, expected.overdraft(), actual.overdraftFeeActive()));
                }
                if (expected.closingExInterest() != null) {
                    DailyAccount daily = result.engine().dailyAccount(snapshot.accountId(), expected.day());
                    compare(failures, where, "closingExInterest", expected.closingExInterest(),
                            daily == null ? null : daily.closingBalanceExcludingInterest());
                }
            }
        }
    }

    // --------------------------------------------------------------- totals

    private static void verifyFinalInterest(LedgerEngine engine, Expectations x, List<String> failures) {
        for (Map.Entry<String, BigDecimal> e : x.finalInterest().entrySet()) {
            compare(failures, "account " + e.getKey(), "capitalized interest",
                    e.getValue(), engine.capitalizedInterest(e.getKey()));
        }
    }

    private static void verifyAuthStatus(LedgerEngine engine, Expectations x, List<String> failures) {
        for (Map.Entry<String, String> e : x.authStatus().entrySet()) {
            Auth auth = engine.auths().get(e.getKey());
            if (auth == null) {
                failures.add("auth %s: expected status %s but the auth was never raised"
                        .formatted(e.getKey(), e.getValue()));
                continue;
            }
            if (!auth.status().name().equals(e.getValue())) {
                failures.add("auth %s: expected %s but was %s (%s)".formatted(
                        e.getKey(), e.getValue(), auth.status(), auth.decisionReason()));
            }
        }
    }

    private static void verifyOverdraft(LedgerEngine engine, Expectations x, List<String> failures) {
        for (Map.Entry<String, BigDecimal> e : x.netOverdraftFees().entrySet()) {
            compare(failures, "account " + e.getKey(), "net overdraft fees",
                    e.getValue(), engine.netOverdraftFees(e.getKey()));
        }
        for (Map.Entry<String, Integer> e : x.overdraftFeeRecordCount().entrySet()) {
            long actual = engine.overdraftFeeRecordCount(e.getKey());
            if (actual != e.getValue()) {
                failures.add("account %s overdraft fee records: expected %d but was %d".formatted(
                        e.getKey(), e.getValue(), actual));
            }
        }
    }

    private static void verifyErrors(LedgerEngine engine, Expectations x, List<String> failures) {
        for (Expectations.ErrorExpectation expected : x.errors()) {
            boolean found = engine.errors().stream().anyMatch(err ->
                    err.eventLabel().equals(expected.eventLabel())
                            && err.code().name().equals(expected.code()));
            if (!found) {
                failures.add("expected %s to be rejected with %s; actual rejections: %s".formatted(
                        expected.eventLabel(), expected.code(),
                        engine.errors().stream().map(EngineError::toString).toList()));
            }
        }
        // Nothing may be rejected that the stream did not say would be.
        for (EngineError actual : engine.errors()) {
            boolean declared = x.errors().stream().anyMatch(e ->
                    e.eventLabel().equals(actual.eventLabel()) && e.code().equals(actual.code().name()));
            if (!declared && !x.errors().isEmpty()) {
                failures.add("unexpected rejection: " + actual);
            }
        }
    }

    private static void verifyTransactions(LedgerEngine engine, EventStream stream,
                                           Expectations x, List<String> failures) {
        for (Expectations.TransactionExpectation expected : x.transactions()) {
            List<BigDecimal> actual = engine.transactionsFor(expected.accountId()).stream()
                    .filter(t -> t.type().name().equals(expected.type()))
                    .filter(t -> t.valueDate().equals(stream.config().dateOfDay(expected.day())))
                    .map(Transaction::signedAmount)
                    .toList();
            if (actual.size() != expected.amounts().size()) {
                failures.add("%s day %d %s: expected %d record(s) %s but found %d %s".formatted(
                        expected.accountId(), expected.day(), expected.type(),
                        expected.amounts().size(), expected.amounts(), actual.size(), actual));
                continue;
            }
            for (int i = 0; i < actual.size(); i++) {
                compare(failures, "%s day %d %s[%d]".formatted(
                                expected.accountId(), expected.day(), expected.type(), i),
                        "amount", expected.amounts().get(i), actual.get(i));
            }
        }
    }

    private static void verifyRecomputePasses(StreamRunResult result, Expectations x, List<String> failures) {
        for (Map.Entry<Integer, Integer> e : x.minRecomputePasses().entrySet()) {
            int actual = result.day(e.getKey()).recomputePasses();
            if (actual < e.getValue()) {
                failures.add("day %d: expected at least %d recompute pass(es) but took %d".formatted(
                        e.getKey(), e.getValue(), actual));
            }
        }
    }

    // ----------------------------------------------------------- invariants

    private static void verifyConvergence(StreamRunResult result, List<String> failures) {
        for (DayReport day : result.days()) {
            if (!day.converged()) {
                failures.add("day %d: reconciliation did not reach a fixed point".formatted(day.day()));
            }
        }
    }

    /**
     * Invariants that must hold for every stream, declared or not: the journal is
     * append-only, every reversal points at a real record, and the derived daily
     * accounts reconcile exactly to the journal.
     */
    private static void verifyAppendOnlyInvariants(StreamRunResult result, List<String> failures) {
        LedgerEngine engine = result.engine();

        for (String accountId : engine.accounts().keySet()) {
            BigDecimal journalSum = engine.transactionsFor(accountId).stream()
                    .map(Transaction::signedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal projected = engine.account(accountId).closingLedgerAmount();
            if (journalSum.compareTo(projected) != 0) {
                failures.add("%s: daily projection %s does not reconcile to the journal sum %s".formatted(
                        accountId, projected.toPlainString(), journalSum.toPlainString()));
            }

            for (Transaction t : engine.transactionsFor(accountId)) {
                if (t.signedAmount().scale() != engine.account(accountId).precision()) {
                    failures.add("%s %s: amount %s is not stored at %d decimal places".formatted(
                            accountId, t.id(), t.signedAmount().toPlainString(),
                            engine.account(accountId).precision()));
                }
            }
        }

        List<String> ids = engine.journal().stream().map(Transaction::id).toList();
        if (ids.size() != ids.stream().distinct().count()) {
            failures.add("journal contains duplicate record ids - records were rewritten, not appended");
        }
    }

    // ------------------------------------------------------------- compare

    private static void compare(List<String> failures, String where, String field,
                                BigDecimal expected, BigDecimal actual) {
        if (expected == null) return;
        if (actual == null) {
            failures.add("%s %s: expected %s but there was no value".formatted(
                    where, field, expected.toPlainString()));
            return;
        }
        if (expected.compareTo(actual) != 0) {
            failures.add("%s %s: expected %s but was %s".formatted(
                    where, field, expected.toPlainString(), actual.toPlainString()));
        }
    }
}
