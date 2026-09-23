package com.mal.ledger.events;

import com.mal.ledger.domain.Currency;
import com.mal.ledger.engine.LedgerConfig;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates the event streams. Each returned stream is a complete, independently
 * executable test iteration: its own accounts, its own config, its own instructions and
 * its own hand-computed expectations. Nothing is shared between iterations, so any one
 * of them can be run, edited or deleted on its own.
 *
 * <p>The expectation figures below are worked out by hand from the non-negotiable rules,
 * not captured from a run - that is what makes them a test rather than a snapshot.
 */
public final class EventStreamFactory {

    public static final LocalDate WEEK_START = LocalDate.of(2026, 1, 1);

    private EventStreamFactory() {
    }

    /** The five iterations, in order. */
    public static List<EventStream> generateAll() {
        return List.of(
                iteration1Baseline(),
                iteration2RetroCascade(),
                iteration3AuthHolds(),
                iteration4Precision(),
                iteration5MixedStress());
    }

    public static EventStream byId(String id) {
        return generateAll().stream()
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such stream: " + id));
    }

    // =====================================================================
    // Iteration 1 - the stream written out in Notes.md, verbatim
    // =====================================================================

    public static EventStream iteration1Baseline() {
        List<LedgerEvent> e = new ArrayList<>();
        e.add(credit("E1", 1, "ACC001", "1200", 1, null));
        e.add(debit("E2", 1, "ACC001", "950", 1, null));
        e.add(auth("E3", 2, "ACC001", "authA", "200", 2, "hold approved: available 250 covers 200"));
        e.add(credit("E4", 3, "ACC001", "400", 3, null));
        e.add(settlement("E5", 4, "ACC001", "authA", "185", 4, "settles below the 200 hold"));
        e.add(settlement("E6", 4, "ACC001", "authZ", "180", 4, "no auth Z was ever raised - must be refused"));
        e.add(debit("E7", 5, "ACC001", "620", 2, "back-dated to day 2: forces reconciliation"));
        e.add(auth("E8", 5, "ACC001", "authB", "90", 5, "refused - available balance is negative after E7"));
        e.add(installment("E10", 5, "ACC002", "10", 3, 5, "10 BHD as 3 equal installments plus a buffer pay"));
        e.add(reversal("E9", 6, "ACC001", "E7", 2,
                "reverses E7 - the debit unwinds, but the two overdraft fees it caused stand"));

        Expectations x = new Expectations();

        snapshot(x, 1, "ACC001", List.of(
                row(1, "250.00", "0.00", "250.00", "0.10")));
        // authA is approved same-day, and holds carry no date constraint (see AMBIGUITIES.md):
        // an approved hold counts for every day in the report, including day 1's row here,
        // not just from the day it was actually raised.
        snapshot(x, 2, "ACC001", List.of(
                row(1, "250.00", "200.00", "50.00", "0.10"),
                row(2, "250.00", "200.00", "50.00", "0.10")));
        snapshot(x, 3, "ACC001", List.of(
                row(3, "650.00", "200.00", "450.00", "0.26")));
        snapshot(x, 4, "ACC001", List.of(
                row(4, "465.00", "0.00", "465.00", "0.18")));

        // Day 5: the back-dated debit restates days 2-4 and pulls two overdraft fees in.
        // By day 5, authA has already settled (day 4) and authB is refused, so no hold is
        // currently approved - every row's holds read 0, including days 2 and 3, which
        // briefly showed 200 in earlier reports while authA was still open.
        snapshot(x, 5, "ACC001", List.of(
                row(1, "250.00", "0.00", "250.00", "0.10"),
                odRow(2, "-370.00", "-395.00", "0.00", "-395.00", "0.00", true),
                odRow(3, "5.00", "5.00", "0.00", "5.00", "0.00", false),
                odRow(4, "-180.00", "-205.00", "0.00", "-205.00", "0.00", true),
                odRow(5, "-205.00", "-205.00", "0.00", "-205.00", "0.00", false)));
        snapshot(x, 5, "ACC002", List.of(
                row(5, "10.000", "0.000", "10.000", "0.004")));

        // Day 6: E9 unwinds E7's own effect (the back-dated debit nets to zero once its
        // reversal lands), but the two overdraft fees it caused are never reversed - they
        // are permanent history once assessed (see REJECTED.md R2). Day 2 and day 4 both
        // carry their fee forever, even though the debit that triggered them is gone.
        snapshot(x, 6, "ACC001", List.of(
                odRow(1, "250.00", "250.00", "0.00", "250.00", "0.10", false),
                odRow(2, "250.00", "225.00", "0.00", "225.00", "0.09", true),
                odRow(3, "625.00", "625.00", "0.00", "625.00", "0.25", false),
                odRow(4, "440.00", "415.00", "0.00", "415.00", "0.16", true),
                odRow(5, "415.00", "415.00", "0.00", "415.00", "0.16", false),
                exInterestRow(6, "415.76", "415.00", "0.00", "415.76", "0.16")));
        snapshot(x, 6, "ACC002", List.of(
                exInterestRow(6, "10.004", "10.000", "0.000", "10.004", "0.004")));

        x.finalInterest().put("ACC001", new BigDecimal("0.76"));
        x.finalInterest().put("ACC002", new BigDecimal("0.004"));
        x.netOverdraftFees().put("ACC001", new BigDecimal("-50.00"));
        x.overdraftFeeRecordCount().put("ACC001", 2);
        x.authStatus().put("authA", "SETTLED");
        x.authStatus().put("authB", "REJECTED");
        x.errors().add(new Expectations.ErrorExpectation("E6", "UNKNOWN_AUTH"));
        x.errors().add(new Expectations.ErrorExpectation("E8", "INSUFFICIENT_AVAILABLE_BALANCE"));
        x.transactions().add(new Expectations.TransactionExpectation(
                "ACC002", 5, "INSTALLMENT_CREDIT", decs("3.333", "3.333", "3.333")));
        x.transactions().add(new Expectations.TransactionExpectation(
                "ACC002", 5, "BALANCING_ADJUSTMENT", decs("0.001")));
        x.minRecomputePasses().put(5, 2);
        x.minRecomputePasses().put(6, 2);

        return new EventStream(
                "iteration-1-baseline",
                "Notes.md baseline - ACC001 AED and ACC002 BHD over 6 days",
                "The stream written out in Notes.md, adjusted for the two later design calls: "
                        + "overdraft fees are permanent once assessed (they survive the later reversal "
                        + "of the debit that caused them) and a hold counts purely on its approval "
                        + "state, with no date window. Covers back-dated reconciliation, an unknown-auth "
                        + "settlement refusal, a hold refused on available balance, and the BHD "
                        + "installment split with its buffer pay.",
                config().build(),
                List.of(new AccountSpec("ACC001", Currency.AED), new AccountSpec("ACC002", Currency.BHD)),
                e, x);
    }

    // =====================================================================
    // Iteration 2 - a huge day-7 reversal restates the whole week
    // =====================================================================

    public static EventStream iteration2RetroCascade() {
        List<LedgerEvent> e = new ArrayList<>();
        e.add(credit("E1", 1, "ACC001", "1000", 1, null));
        e.add(debit("E2", 2, "ACC001", "300", 2, null));
        e.add(credit("E3", 3, "ACC001", "500", 3, null));
        e.add(debit("E4", 4, "ACC001", "200", 4, null));
        e.add(debit("E5", 5, "ACC001", "100", 5, null));
        e.add(debit("E6", 7, "ACC001", "2000", 3,
                "day 7: huge back-dated debit into day 3 - drags days 3-6 overdrawn and "
                        + "shrinks the already-capitalized interest"));
        e.add(reversal("E7", 8, "ACC001", "E6", 3,
                "day 8: reverses it - the debit unwinds and interest is restated upwards again, "
                        + "but the four overdraft fees it caused never reverse"));

        Expectations x = new Expectations();

        // Day 6: clean week, interest capitalized at 1.92.
        snapshot(x, 6, "ACC001", List.of(
                row(1, "1000.00", "0.00", "1000.00", "0.40"),
                row(2, "700.00", "0.00", "700.00", "0.28"),
                row(3, "1200.00", "0.00", "1200.00", "0.48"),
                row(4, "1000.00", "0.00", "1000.00", "0.40"),
                row(5, "900.00", "0.00", "900.00", "0.36"),
                exInterestRow(6, "901.92", "900.00", "0.00", "901.92", "0.36")));

        // Day 7: days 3,4,5,6 all close negative; interest falls back to 0.68.
        snapshot(x, 7, "ACC001", List.of(
                odRow(1, "1000.00", "1000.00", "0.00", "1000.00", "0.40", false),
                odRow(2, "700.00", "700.00", "0.00", "700.00", "0.28", false),
                odRow(3, "-800.00", "-825.00", "0.00", "-825.00", "0.00", true),
                odRow(4, "-1025.00", "-1050.00", "0.00", "-1050.00", "0.00", true),
                odRow(5, "-1150.00", "-1175.00", "0.00", "-1175.00", "0.00", true),
                odRow(6, "-1174.32", "-1199.32", "0.00", "-1199.32", "0.00", true),
                odRow(7, "-1199.32", "-1199.32", "0.00", "-1199.32", "0.00", false)));

        // Day 8: E7 unwinds E6's own effect (the 2000 debit nets to zero once its reversal
        // lands), but the four overdraft fees it caused along the way never reverse - each
        // one stands as its own permanent record, so the balance settles 100 AED (four
        // fees) below where a debit that had never happened would have left it.
        snapshot(x, 8, "ACC001", List.of(
                odRow(3, "1200.00", "1175.00", "0.00", "1175.00", "0.47", true),
                odRow(4, "975.00", "950.00", "0.00", "950.00", "0.38", true),
                odRow(5, "850.00", "825.00", "0.00", "825.00", "0.33", true),
                exInterestRow(6, "801.86", "800.00", "0.00", "801.86", "0.32"),
                row(7, "801.86", "0.00", "801.86", "0.32"),
                row(8, "801.86", "0.00", "801.86", "0.32")));

        x.finalInterest().put("ACC001", new BigDecimal("1.86"));
        x.netOverdraftFees().put("ACC001", new BigDecimal("-100.00"));
        x.overdraftFeeRecordCount().put("ACC001", 4);
        x.minRecomputePasses().put(7, 2);
        x.minRecomputePasses().put(8, 2);

        return new EventStream(
                "iteration-2-retro-cascade",
                "Day-7 reversal cascade - interest reversed and re-credited twice, fees permanent",
                "An 8-day window with capitalization still on day 6. A 2000 AED debit back-dated "
                        + "into day 3 arrives on day 7, after interest has already been credited: four "
                        + "overdraft fees are raised, the old interest credit is reversed and a smaller "
                        + "one posted. Day 8 reverses the debit and interest is restated upwards again - "
                        + "but the four fees do not reverse with it, so the account settles 100 AED "
                        + "below where it would have closed had the debit never arrived.",
                config().windowDays(8).build(),
                List.of(new AccountSpec("ACC001", Currency.AED)),
                e, x);
    }

    // =====================================================================
    // Iteration 3 - authorization and hold semantics
    // =====================================================================

    public static EventStream iteration3AuthHolds() {
        List<LedgerEvent> e = new ArrayList<>();
        e.add(credit("E1", 1, "ACC001", "500", 1, null));
        e.add(auth("E2", 1, "ACC001", "authA", "200", 1, "approved: 500 - 0 - 200 = 300"));
        e.add(auth("E3", 2, "ACC001", "authB", "400", 2, "refused: 500 - 200 - 400 = -100"));
        e.add(auth("E4", 2, "ACC001", "authC", "300", 2, "approved at exactly zero headroom"));
        e.add(settlement("E5", 3, "ACC001", "authA", "250", 3, "refused: settlement exceeds the 200 hold"));
        e.add(settlement("E6", 3, "ACC001", "authA", "200", 3, "settles in full"));
        e.add(settlement("E7", 4, "ACC001", "authA", "50", 4, "refused: authA already settled"));
        e.add(settlement("E8", 4, "ACC001", "authB", "100", 4, "refused: authB was never approved"));
        e.add(settlement("E9", 4, "ACC001", "authC", "300", 4, "settles, taking the ledger to exactly zero"));
        e.add(auth("E10", 5, "ACC001", "authD", "10", 5, "refused: nothing available"));
        e.add(credit("E11", 5, "ACC001", "100", 5, null));
        e.add(auth("E12", 5, "ACC001", "authE", "100", 5, "approved; never settled in this window"));
        e.add(settlement("E13", 6, "ACC001", "authZZ", "5", 6, "refused: unknown auth id"));

        Expectations x = new Expectations();
        snapshot(x, 1, "ACC001", List.of(row(1, "500.00", "200.00", "300.00", "0.20")));
        snapshot(x, 2, "ACC001", List.of(row(2, "500.00", "500.00", "0.00", "0.20")));
        // Holds carry no date constraint: day 3's report shows every row against the
        // holds approved as of day 3 (authA just settled today, authC's 300 remains) -
        // not what was actually active on days 1-2 when they were first reported.
        snapshot(x, 3, "ACC001", List.of(
                row(1, "500.00", "300.00", "200.00", "0.20"),
                row(2, "500.00", "300.00", "200.00", "0.20"),
                row(3, "300.00", "300.00", "0.00", "0.12")));
        snapshot(x, 4, "ACC001", List.of(odRow(4, "0.00", "0.00", "0.00", "0.00", "0.00", false)));
        snapshot(x, 5, "ACC001", List.of(row(5, "100.00", "100.00", "0.00", "0.04")));
        snapshot(x, 6, "ACC001", List.of(exInterestRow(6, "100.56", "100.00", "100.00", "0.56", "0.04")));

        x.finalInterest().put("ACC001", new BigDecimal("0.56"));
        x.netOverdraftFees().put("ACC001", new BigDecimal("0.00"));
        x.overdraftFeeRecordCount().put("ACC001", 0);
        x.authStatus().put("authA", "SETTLED");
        x.authStatus().put("authB", "REJECTED");
        x.authStatus().put("authC", "SETTLED");
        x.authStatus().put("authD", "REJECTED");
        x.authStatus().put("authE", "APPROVED");
        x.errors().add(new Expectations.ErrorExpectation("E3", "INSUFFICIENT_AVAILABLE_BALANCE"));
        x.errors().add(new Expectations.ErrorExpectation("E5", "SETTLEMENT_EXCEEDS_AUTH"));
        x.errors().add(new Expectations.ErrorExpectation("E7", "AUTH_ALREADY_SETTLED"));
        x.errors().add(new Expectations.ErrorExpectation("E8", "AUTH_NOT_APPROVED"));
        x.errors().add(new Expectations.ErrorExpectation("E10", "INSUFFICIENT_AVAILABLE_BALANCE"));
        x.errors().add(new Expectations.ErrorExpectation("E13", "UNKNOWN_AUTH"));

        return new EventStream(
                "iteration-3-auth-holds",
                "Authorization and hold lifecycle - every rejection path",
                "No back-dating and no overdraft: this iteration isolates the auth rules. Holds "
                        + "approved at positive and at exactly zero headroom, a hold refused, settlements "
                        + "refused for exceeding the hold, for a rejected auth, for an already-settled "
                        + "auth and for an unknown auth id, and a hold left open at the end of the window.",
                config().build(),
                List.of(new AccountSpec("ACC001", Currency.AED)),
                e, x);
    }

    // =====================================================================
    // Iteration 4 - precision, installment remainders, accrual nullification
    // =====================================================================

    public static EventStream iteration4Precision() {
        List<LedgerEvent> e = new ArrayList<>();
        e.add(installment("E1", 1, "BHD001", "10", 3, 1, "10 BHD / 3 -> 3.333 x3 with a 0.001 buffer pay"));
        e.add(credit("E2", 1, "BHD002", "1.000", 1, "1 BHD accrues 0.0004/day - rounds to zero every day"));
        e.add(credit("E3", 1, "AED003", "10.00", 1, "10 AED accrues 0.004/day - rounds to zero every day"));
        e.add(installment("E4", 3, "BHD001", "1.000", 7, 3, "1 BHD / 7 -> 0.142 x7 with a 0.006 buffer pay"));
        e.add(debit("E5", 4, "BHD002", "5.000", 4, "drives day 4 overdrawn - 2.500 BHD fee"));
        e.add(credit("E6", 5, "BHD002", "10.000", 5, "day 5 recovers, but day 4 keeps its fee"));

        Expectations x = new Expectations();

        snapshot(x, 6, "BHD001", List.of(
                row(1, "10.000", "0.000", "10.000", "0.004"),
                row(2, "10.000", "0.000", "10.000", "0.004"),
                row(3, "11.000", "0.000", "11.000", "0.004"),
                row(4, "11.000", "0.000", "11.000", "0.004"),
                row(5, "11.000", "0.000", "11.000", "0.004"),
                exInterestRow(6, "11.020", "11.000", "0.000", "11.020", "0.004")));

        snapshot(x, 6, "BHD002", List.of(
                row(1, "1.000", "0.000", "1.000", "0.000"),
                row(2, "1.000", "0.000", "1.000", "0.000"),
                row(3, "1.000", "0.000", "1.000", "0.000"),
                odRow(4, "-4.000", "-6.500", "0.000", "-6.500", "0.000", true),
                odRow(5, "3.500", "3.500", "0.000", "3.500", "0.001", false),
                exInterestRow(6, "3.501", "3.500", "0.000", "3.501", "0.001")));

        // Rounding down nullifies the interest completely: 5 x 0.004 raw, 0.00 stored.
        snapshot(x, 6, "AED003", List.of(
                row(1, "10.00", "0.00", "10.00", "0.00"),
                row(5, "10.00", "0.00", "10.00", "0.00"),
                row(6, "10.00", "0.00", "10.00", "0.00")));

        x.finalInterest().put("BHD001", new BigDecimal("0.020"));
        x.finalInterest().put("BHD002", new BigDecimal("0.001"));
        x.finalInterest().put("AED003", new BigDecimal("0.00"));
        x.netOverdraftFees().put("BHD002", new BigDecimal("-2.500"));
        x.overdraftFeeRecordCount().put("BHD002", 1);
        x.overdraftFeeRecordCount().put("BHD001", 0);
        x.transactions().add(new Expectations.TransactionExpectation(
                "BHD001", 1, "INSTALLMENT_CREDIT", decs("3.333", "3.333", "3.333")));
        x.transactions().add(new Expectations.TransactionExpectation(
                "BHD001", 1, "BALANCING_ADJUSTMENT", decs("0.001")));
        x.transactions().add(new Expectations.TransactionExpectation(
                "BHD001", 3, "INSTALLMENT_CREDIT",
                decs("0.142", "0.142", "0.142", "0.142", "0.142", "0.142", "0.142")));
        x.transactions().add(new Expectations.TransactionExpectation(
                "BHD001", 3, "BALANCING_ADJUSTMENT", decs("0.006")));

        return new EventStream(
                "iteration-4-precision",
                "Currency precision, installment remainders and accrual nullification",
                "Three accounts at two precisions. Two installment splits whose remainders do not "
                        + "vanish, a BHD overdraft fee on a day that later recovers, and two balances "
                        + "small enough that the daily accrual rounds to zero every single day - the "
                        + "nullification Notes.md flags as an ambiguity, made visible and asserted.",
                config().build(),
                List.of(new AccountSpec("BHD001", Currency.BHD),
                        new AccountSpec("BHD002", Currency.BHD),
                        new AccountSpec("AED003", Currency.AED)),
                e, x);
    }

    // =====================================================================
    // Iteration 5 - both currencies, everything at once
    // =====================================================================

    public static EventStream iteration5MixedStress() {
        List<LedgerEvent> e = new ArrayList<>();
        e.add(credit("E1", 1, "ACC001", "800", 1, null));
        e.add(credit("E2", 1, "ACC002", "20.000", 1, null));
        e.add(auth("E3", 2, "ACC001", "authA", "300", 2, "approved against 800"));
        e.add(debit("E4", 2, "ACC002", "25.000", 2, "BHD account goes overdrawn - 2.500 fee"));
        e.add(debit("E5", 3, "ACC001", "900", 1, "back-dated into day 1 - fee on day 1 only"));
        e.add(credit("E6", 3, "ACC002", "30.000", 3, "recovers, day 2 keeps its fee"));
        e.add(reversal("E7", 4, "ACC001", "E5", 1,
                "unwinds the back-dated debit itself, but not the fee it caused"));
        e.add(settlement("E8", 4, "ACC001", "authA", "300", 4, "settles in full"));
        e.add(settlement("E9", 5, "ACC001", "authQ", "50", 5, "refused: unknown auth id"));
        e.add(auth("E10", 5, "ACC001", "authB", "600", 5, "refused: only 475 available"));
        e.add(auth("E11", 5, "ACC001", "authC", "500", 5,
                "refused: day 1's fee is permanent, so only 475 is available, not 500"));
        e.add(installment("E12", 6, "ACC002", "7.000", 4, 6, "7 BHD / 4 -> 1.750 x4, no remainder"));

        Expectations x = new Expectations();

        snapshot(x, 2, "ACC001", List.of(row(2, "800.00", "300.00", "500.00", "0.32")));
        snapshot(x, 2, "ACC002", List.of(odRow(2, "-5.000", "-7.500", "0.000", "-7.500", "0.000", true)));

        snapshot(x, 3, "ACC001", List.of(
                odRow(1, "-100.00", "-125.00", "300.00", "-425.00", "0.00", true),
                odRow(2, "-125.00", "-125.00", "300.00", "-425.00", "0.00", false),
                odRow(3, "-125.00", "-125.00", "300.00", "-425.00", "0.00", false)));
        snapshot(x, 3, "ACC002", List.of(odRow(3, "22.500", "22.500", "0.000", "22.500", "0.009", false)));

        // Day 4: E7 unwinds E5's own effect on the ledger, but day 1's fee is permanent -
        // it stands forever, on every row, even the days before day 1 ever went negative
        // in this restated view.
        snapshot(x, 4, "ACC001", List.of(
                odRow(1, "800.00", "775.00", "0.00", "775.00", "0.31", true),
                odRow(2, "775.00", "775.00", "0.00", "775.00", "0.31", false),
                odRow(3, "775.00", "775.00", "0.00", "775.00", "0.31", false),
                odRow(4, "475.00", "475.00", "0.00", "475.00", "0.19", false)));

        // Day 5: available is 475, not 500 - day 1's permanent fee ate 25 out of the
        // running balance, so authC's 500 hold request no longer clears (475 - 500 < 0).
        snapshot(x, 5, "ACC001", List.of(row(5, "475.00", "0.00", "475.00", "0.19")));

        snapshot(x, 6, "ACC001", List.of(exInterestRow(6, "476.31", "475.00", "0.00", "476.31", "0.19")));
        snapshot(x, 6, "ACC002", List.of(exInterestRow(6, "29.535", "29.500", "0.000", "29.535", "0.011")));

        x.finalInterest().put("ACC001", new BigDecimal("1.31"));
        x.finalInterest().put("ACC002", new BigDecimal("0.035"));
        x.netOverdraftFees().put("ACC001", new BigDecimal("-25.00"));
        x.netOverdraftFees().put("ACC002", new BigDecimal("-2.500"));
        x.overdraftFeeRecordCount().put("ACC001", 1);
        x.overdraftFeeRecordCount().put("ACC002", 1);
        x.authStatus().put("authA", "SETTLED");
        x.authStatus().put("authB", "REJECTED");
        x.authStatus().put("authC", "REJECTED");
        x.errors().add(new Expectations.ErrorExpectation("E9", "UNKNOWN_AUTH"));
        x.errors().add(new Expectations.ErrorExpectation("E10", "INSUFFICIENT_AVAILABLE_BALANCE"));
        x.errors().add(new Expectations.ErrorExpectation("E11", "INSUFFICIENT_AVAILABLE_BALANCE"));
        x.transactions().add(new Expectations.TransactionExpectation(
                "ACC002", 6, "INSTALLMENT_CREDIT", decs("1.750", "1.750", "1.750", "1.750")));

        return new EventStream(
                "iteration-5-mixed-stress",
                "Both currencies, both fee directions, a permanent fee that costs a later hold",
                "AED and BHD side by side. One account takes a back-dated debit that raises a fee, "
                        + "then reverses the debit itself - but not the fee, which is permanent, and "
                        + "whose 25 AED is still missing three days later when a hold that would "
                        + "otherwise have cleared is refused instead; a live hold spans the restatement "
                        + "throughout. The other account keeps a fee on a day that never recovers. Ends "
                        + "with an installment split that divides exactly, so no buffer pay is raised.",
                config().build(),
                List.of(new AccountSpec("ACC001", Currency.AED), new AccountSpec("ACC002", Currency.BHD)),
                e, x);
    }

    // =====================================================================
    // Small DSL
    // =====================================================================

    private static LedgerConfig.Builder config() {
        return LedgerConfig.builder().weekStartDate(WEEK_START).windowDays(6).capitalizationDay(6);
    }

    private static LedgerEvent credit(String label, int day, String account, String amount, int valueDay, String note) {
        return LedgerEvent.builder(label, day, account, EventType.CREDIT)
                .amount(amount).valueDay(valueDay).note(note).build();
    }

    private static LedgerEvent debit(String label, int day, String account, String amount, int valueDay, String note) {
        return LedgerEvent.builder(label, day, account, EventType.DEBIT)
                .amount(amount).valueDay(valueDay).note(note).build();
    }

    private static LedgerEvent auth(String label, int day, String account, String authId, String amount,
                                    int valueDay, String note) {
        return LedgerEvent.builder(label, day, account, EventType.AUTH)
                .amount(amount).authId(authId).valueDay(valueDay).note(note).build();
    }

    private static LedgerEvent settlement(String label, int day, String account, String authId, String amount,
                                          int valueDay, String note) {
        return LedgerEvent.builder(label, day, account, EventType.SETTLEMENT)
                .amount(amount).authId(authId).valueDay(valueDay).note(note).build();
    }

    private static LedgerEvent reversal(String label, int day, String account, String target, int valueDay, String note) {
        return LedgerEvent.builder(label, day, account, EventType.REVERSAL)
                .target(target).valueDay(valueDay).note(note).build();
    }

    private static LedgerEvent installment(String label, int day, String account, String amount, int slices,
                                           int valueDay, String note) {
        return LedgerEvent.builder(label, day, account, EventType.INSTALLMENT_CREDIT)
                .amount(amount).installments(slices).valueDay(valueDay).note(note).build();
    }

    private static void snapshot(Expectations x, int afterDay, String account, List<Expectations.Row> rows) {
        x.snapshots().add(new Expectations.Snapshot(afterDay, account, rows));
    }

    private static Expectations.Row row(int day, String closing, String holds, String available, String accrual) {
        return new Expectations.Row(day, dec(closing), null, dec(available), dec(holds), dec(accrual), null, null);
    }

    private static Expectations.Row odRow(int day, String assessment, String closing, String holds,
                                          String available, String accrual, boolean overdraft) {
        return new Expectations.Row(day, dec(closing), null, dec(available), dec(holds), dec(accrual),
                dec(assessment), overdraft);
    }

    private static Expectations.Row exInterestRow(int day, String closing, String closingExInterest,
                                                  String holds, String available, String accrual) {
        return new Expectations.Row(day, dec(closing), dec(closingExInterest), dec(available), dec(holds),
                dec(accrual), null, null);
    }

    private static BigDecimal dec(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private static List<BigDecimal> decs(String... values) {
        List<BigDecimal> out = new ArrayList<>();
        for (String v : values) out.add(new BigDecimal(v));
        return List.copyOf(out);
    }
}
