package com.mal.ledger.report;

import com.mal.ledger.domain.Money;
import com.mal.ledger.domain.Transaction;
import com.mal.ledger.engine.EngineError;
import com.mal.ledger.events.LedgerEvent;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.List;

/** Prints the per-day answer the problem asks for, plus the restated history. */
public final class ReportPrinter {

    private static final String RULE = "=".repeat(96);
    private static final String THIN = "-".repeat(96);

    private final PrintStream out;

    public ReportPrinter(PrintStream out) {
        this.out = out;
    }

    public void print(StreamRunResult result) {
        var stream = result.stream();
        out.println(RULE);
        out.printf("STREAM %s - %s%n", stream.id(), stream.name());
        if (stream.description() != null && !stream.description().isBlank()) {
            out.println("  " + stream.description());
        }
        out.printf("  week start %s | window %d days | capitalize on day %d | rate %s/day%n",
                stream.config().weekStartDate(), stream.config().windowDays(),
                stream.config().capitalizationDay(), stream.config().dailyInterestRate().toPlainString());
        out.printf("  rounding: store=%s accrual=%s installment=%s%n",
                stream.config().storeRounding(), stream.config().accrualRounding(),
                stream.config().installmentRounding());
        out.println(RULE);

        for (DayReport day : result.days()) {
            printDay(result, day);
        }
        printSummary(result);
    }

    private void printDay(StreamRunResult result, DayReport day) {
        out.println();
        out.printf("### DAY %d (%s)%s%n", day.day(), day.date(),
                day.restatedHistory() ? "   << back-dated record: history restated" : "");
        out.println(THIN);

        List<LedgerEvent> events = result.stream().eventsOn(day.day());
        if (events.isEmpty()) {
            out.println("  instructions : (none)");
        } else {
            out.println("  instructions :");
            for (LedgerEvent e : events) {
                out.printf("      %-4s %-10s %-18s %s%s%n", e.label(), e.accountId(), e.type(),
                        describe(e), e.isBackdated() ? "   (back-dated to d" + e.valueDay() + ")" : "");
            }
        }

        for (AccountDayView view : day.accounts()) {
            out.println();
            out.printf("  %s [%s, %d dp]%n", view.accountId(), view.currency(), view.currency().precision());
            out.printf("      closing ledger balance : %s%n", fmt(view.closingBalance(), view));
            if (view.capitalizedInterest().signum() != 0) {
                out.printf("        of which interest    : %s (ex-interest %s)%n",
                        fmt(view.capitalizedInterest(), view), fmt(view.closingBalanceExcludingInterest(), view));
            }
            out.printf("      active holds           : %s%n", fmt(view.activeHolds(), view));
            out.printf("      available balance      : %s%n", fmt(view.availableBalance(), view));
            out.printf("      interest accrued today : %s   (to date %s)%n",
                    fmt(view.accrualToday(), view), fmt(view.accrualToDate(), view));
            out.printf("      overdraft fees         : net %s across %d assessment(s)%s%n",
                    fmt(view.netOverdraftFees(), view), view.overdraftFeeCount(),
                    view.overdraftEnabled() ? "   [fee live on this day]" : "");
            out.printf("      auth states            : %s%n",
                    view.authStates().isEmpty() ? "(none)" : String.join("; ", view.authStates()));

            if (day.restatedHistory() || hasOverdraft(view)) {
                out.println("      ledger history (restated as of this day):");
                out.println("          day |     opening |  assessment |     closing |       holds |   available |    accrual | od");
                for (AccountDayView.LedgerRow row : view.history()) {
                    out.printf("          %3d | %11s | %11s | %11s | %11s | %11s | %10s | %s%n",
                            row.day(), row.opening().toPlainString(), row.assessmentBalance().toPlainString(),
                            row.closing().toPlainString(), row.holds().toPlainString(),
                            row.available().toPlainString(), row.accrual().toPlainString(),
                            row.overdraftFeeActive() ? "FEE" : "-");
                }
            }
        }

        if (!day.systemGenerated().isEmpty()) {
            out.println();
            out.println("  fee / interest assessments raised today:");
            for (Transaction t : day.systemGenerated()) {
                out.printf("      %-4s %-10s %-34s %12s  value_date=%s%n",
                        t.id(), t.accountId(), t.type(), t.signedAmount().toPlainString(), t.valueDate());
            }
        }

        out.println();
        if (day.errors().isEmpty()) {
            out.println("  errors       : (none)");
        } else {
            out.println("  errors       :");
            for (EngineError e : day.errors()) {
                out.printf("      %-4s %-10s %-32s %s%n", e.eventLabel(), e.accountId(), e.code(), e.message());
            }
        }
        out.printf("  recompute    : %d pass(es)%s%n", day.recomputePasses(),
                day.converged() ? "" : "   !! DID NOT CONVERGE");
    }

    private void printSummary(StreamRunResult result) {
        out.println();
        out.println(RULE);
        out.println("END OF WINDOW SUMMARY");
        out.println(RULE);
        DayReport last = result.days().get(result.days().size() - 1);
        for (AccountDayView view : last.accounts()) {
            out.printf("  %-10s %-4s closing=%-14s available=%-14s interest capitalized=%-10s overdraft net=%s%n",
                    view.accountId(), view.currency(),
                    fmt(view.closingBalance(), view), fmt(view.availableBalance(), view),
                    fmt(view.capitalizedInterest(), view), fmt(view.netOverdraftFees(), view));
        }
        out.printf("  journal      : %d append-only records, 0 updates, 0 deletions%n",
                result.engine().journal().size());
        out.printf("  rejections   : %d%n", result.errors().size());
        out.println();
    }

    private boolean hasOverdraft(AccountDayView view) {
        return view.overdraftFeeCount() > 0;
    }

    private String fmt(BigDecimal value, AccountDayView view) {
        return Money.format(value, view.currency());
    }

    private String describe(LedgerEvent e) {
        StringBuilder sb = new StringBuilder();
        if (e.amount() != null) sb.append(e.amount().toPlainString());
        if (e.installments() > 1) sb.append(" in ").append(e.installments()).append(" installments");
        if (e.authId() != null) sb.append(" auth=").append(e.authId());
        if (e.targetLabel() != null) sb.append(" reverses ").append(e.targetLabel());
        if (e.note() != null) sb.append("  // ").append(e.note());
        return sb.toString();
    }
}
