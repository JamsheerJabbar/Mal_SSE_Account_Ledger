package com.mal.ledger.runner;

import com.mal.ledger.domain.Account;
import com.mal.ledger.domain.Auth;
import com.mal.ledger.domain.DailyAccount;
import com.mal.ledger.domain.Money;
import com.mal.ledger.domain.Transaction;
import com.mal.ledger.engine.LedgerEngine;
import com.mal.ledger.engine.RecomputeResult;
import com.mal.ledger.events.AccountSpec;
import com.mal.ledger.events.EventStream;
import com.mal.ledger.events.LedgerEvent;
import com.mal.ledger.report.AccountDayView;
import com.mal.ledger.report.DayReport;
import com.mal.ledger.report.StreamRunResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Walks an event stream day by day and produces the per-day report.
 *
 * <p>Each day: apply the day's instructions in declaration order (ACC001 first, then
 * ACC002), reconciling immediately after any instruction that back-dates a record so the
 * next authorization decision sees a consistent ledger; then close the day, which
 * assesses the overdraft fee through today and, from the capitalization day onwards,
 * reconciles the interest credit.
 */
public final class StreamRunner {

    public StreamRunResult run(EventStream stream) {
        LedgerEngine engine = new LedgerEngine(stream.config());
        for (AccountSpec spec : stream.accounts()) {
            engine.openAccount(spec);
        }

        List<DayReport> reports = new ArrayList<>();
        int lastDay = stream.lastProcessedDay();
        for (int day = 1; day <= lastDay; day++) {
            int journalMark = engine.journal().size();
            boolean restated = false;
            int intraDayPasses = 0;

            for (LedgerEvent event : stream.eventsOn(day)) {
                boolean backdated = engine.apply(event);
                if (backdated) {
                    RecomputeResult r = engine.reconcileIntraDay(day);
                    intraDayPasses += r.passes();
                    restated = true;
                }
            }

            RecomputeResult close = engine.closeDay(day);
            reports.add(buildReport(engine, stream, day, journalMark, restated,
                    intraDayPasses + close.passes(), close.converged()));
        }
        return new StreamRunResult(stream, engine, List.copyOf(reports));
    }

    private DayReport buildReport(LedgerEngine engine, EventStream stream, int day,
                                  int journalMark, boolean restated, int passes, boolean converged) {
        LocalDate date = stream.config().dateOfDay(day);
        List<Transaction> newRecords = new ArrayList<>(engine.journal().subList(journalMark, engine.journal().size()));
        List<Transaction> systemGenerated = newRecords.stream().filter(Transaction::isSystemGenerated).toList();

        List<AccountDayView> views = new ArrayList<>();
        for (Account account : engine.accounts().values()) {
            views.add(buildAccountView(engine, account, day, date));
        }
        return new DayReport(day, date, List.copyOf(views), engine.errorsOn(day),
                List.copyOf(newRecords), systemGenerated, passes, restated, converged);
    }

    private AccountDayView buildAccountView(LedgerEngine engine, Account account, int day, LocalDate date) {
        List<AccountDayView.LedgerRow> history = new ArrayList<>();
        BigDecimal accrualToDate = Money.zero(account.currency());
        for (Map.Entry<LocalDate, DailyAccount> e : engine.dailyAccounts(account.id()).entrySet()) {
            if (e.getKey().isAfter(date)) break;
            DailyAccount d = e.getValue();
            history.add(new AccountDayView.LedgerRow(
                    engine.config().dayOfDate(e.getKey()),
                    d.openingBalance(), d.assessmentBalance(), d.closingBalance(),
                    d.activeHoldsTotal(), d.availableBalance(), d.interestAccrual(),
                    d.overdraftEnabled()));
            accrualToDate = accrualToDate.add(d.interestAccrual());
        }

        DailyAccount today = engine.dailyAccount(account.id(), date);
        List<String> authStates = new ArrayList<>();
        for (Auth auth : engine.auths().values()) {
            if (!auth.accountId().equals(account.id())) continue;
            if (auth.decisionDate().isAfter(date)) continue;
            authStates.add("%s=%s hold=%s vd=d%d%s".formatted(
                    auth.id(), auth.status(), auth.holdAmount().toPlainString(),
                    engine.config().dayOfDate(auth.holdValueDate()),
                    auth.isActiveOn(date) ? " [active]" : ""));
        }

        return new AccountDayView(
                account.id(),
                account.currency(),
                today == null ? Money.zero(account.currency()) : today.closingBalance(),
                today == null ? Money.zero(account.currency()) : today.closingBalanceExcludingInterest(),
                today == null ? Money.zero(account.currency()) : today.availableBalance(),
                today == null ? Money.zero(account.currency()) : today.activeHoldsTotal(),
                today == null ? Money.zero(account.currency()) : today.interestAccrual(),
                accrualToDate,
                engine.capitalizedInterest(account.id()),
                engine.netOverdraftFees(account.id()),
                engine.overdraftFeeRecordCount(account.id()),
                today != null && today.overdraftEnabled(),
                List.copyOf(history),
                List.copyOf(authStates));
    }
}
