package com.mal.ledger.io;

import com.mal.ledger.domain.Currency;
import com.mal.ledger.engine.LedgerConfig;
import com.mal.ledger.events.AccountSpec;
import com.mal.ledger.events.EventStream;
import com.mal.ledger.events.EventType;
import com.mal.ledger.events.Expectations;
import com.mal.ledger.events.LedgerEvent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads and writes event streams as hand-editable JSON. Every decimal is stored as a
 * string so the scale survives the round trip (0.333 stays three-decimal BHD, not 0.333d).
 */
public final class EventStreamStore {

    public static final String DEFAULT_DIR_PROPERTY = "ledger.streams.dir";
    public static final String DEFAULT_DIR = "event-streams";

    private EventStreamStore() {
    }

    /** Directory holding the stream files; override with -Dledger.streams.dir=... */
    public static Path defaultDirectory() {
        return Path.of(System.getProperty(DEFAULT_DIR_PROPERTY, DEFAULT_DIR));
    }

    // ------------------------------------------------------------------ read

    public static List<EventStream> readAll(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(EventStreamStore::read)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list event streams in " + dir.toAbsolutePath(), e);
        }
    }

    public static EventStream read(Path file) {
        try {
            return fromJson(Json.parseObject(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read event stream " + file, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Invalid event stream " + file + ": " + e.getMessage(), e);
        }
    }

    public static EventStream fromJson(Map<String, Object> root) {
        LedgerConfig config = configFromJson(Json.obj(root, "config"));

        List<AccountSpec> accounts = new ArrayList<>();
        for (Map<String, Object> a : Json.objList(root, "accounts")) {
            accounts.add(new AccountSpec(Json.str(a, "id"), Currency.of(Json.str(a, "currency"))));
        }

        List<LedgerEvent> events = new ArrayList<>();
        for (Map<String, Object> e : Json.objList(root, "events")) {
            int day = Json.intVal(e, "day", 1);
            events.add(new LedgerEvent(
                    Json.str(e, "label"),
                    day,
                    Json.intVal(e, "valueDay", day),
                    Json.str(e, "account"),
                    EventType.valueOf(Json.str(e, "type")),
                    Json.dec(e, "amount"),
                    Json.str(e, "authId"),
                    Json.str(e, "target"),
                    Json.intVal(e, "installments", 1),
                    Json.str(e, "note")));
        }

        return new EventStream(
                Json.str(root, "id"),
                Json.str(root, "name", Json.str(root, "id")),
                Json.str(root, "description", ""),
                config,
                accounts,
                events,
                expectationsFromJson(Json.obj(root, "expectations")));
    }

    private static LedgerConfig configFromJson(Map<String, Object> c) {
        LedgerConfig.Builder b = LedgerConfig.builder();
        if (c.containsKey("weekStartDate")) b.weekStartDate(LocalDate.parse(Json.str(c, "weekStartDate")));
        b.windowDays(Json.intVal(c, "windowDays", 6));
        b.capitalizationDay(Json.intVal(c, "capitalizationDay", 6));
        if (c.containsKey("dailyInterestRate")) b.dailyInterestRate(Json.dec(c, "dailyInterestRate"));
        if (c.containsKey("accrualRounding")) b.accrualRounding(RoundingMode.valueOf(Json.str(c, "accrualRounding")));
        if (c.containsKey("storeRounding")) b.storeRounding(RoundingMode.valueOf(Json.str(c, "storeRounding")));
        if (c.containsKey("installmentRounding")) b.installmentRounding(RoundingMode.valueOf(Json.str(c, "installmentRounding")));
        b.capitalizationExcludesOwnDay(Json.boolVal(c, "capitalizationExcludesOwnDay", true));
        b.overdraftAssessmentExcludesOwnDayFee(Json.boolVal(c, "overdraftAssessmentExcludesOwnDayFee", true));
        b.overdraftFeeOnDaysWithoutMovement(Json.boolVal(c, "overdraftFeeOnDaysWithoutMovement", false));
        b.discardAccrualRemainder(Json.boolVal(c, "discardAccrualRemainder", false));
        b.maxRecomputePasses(Json.intVal(c, "maxRecomputePasses", 16));
        b.maxRecalculationCycles(Json.intVal(c, "maxRecalculationCycles", 3));
        Map<String, Object> fees = Json.obj(c, "overdraftFees");
        for (Map.Entry<String, Object> e : fees.entrySet()) {
            b.overdraftFee(Currency.of(e.getKey()), new BigDecimal(String.valueOf(e.getValue())));
        }
        return b.build();
    }

    private static Expectations expectationsFromJson(Map<String, Object> x) {
        Expectations exp = new Expectations();
        if (x.isEmpty()) return exp;

        for (Map<String, Object> s : Json.objList(x, "snapshots")) {
            List<Expectations.Row> rows = new ArrayList<>();
            for (Map<String, Object> r : Json.objList(s, "rows")) {
                rows.add(new Expectations.Row(
                        Json.intVal(r, "day", 0),
                        Json.dec(r, "closing"),
                        Json.dec(r, "closingExInterest"),
                        Json.dec(r, "available"),
                        Json.dec(r, "holds"),
                        Json.dec(r, "accrual"),
                        Json.dec(r, "assessmentBalance"),
                        r.containsKey("overdraft") ? Json.boolVal(r, "overdraft", false) : null));
            }
            exp.snapshots().add(new Expectations.Snapshot(
                    Json.intVal(s, "afterDay", 0), Json.str(s, "account"), List.copyOf(rows)));
        }

        Json.obj(x, "finalInterest").forEach((k, v) -> exp.finalInterest().put(k, new BigDecimal(String.valueOf(v))));
        Json.obj(x, "authStatus").forEach((k, v) -> exp.authStatus().put(k, String.valueOf(v)));
        Json.obj(x, "netOverdraftFees").forEach((k, v) -> exp.netOverdraftFees().put(k, new BigDecimal(String.valueOf(v))));
        Json.obj(x, "overdraftFeeRecordCount").forEach((k, v) ->
                exp.overdraftFeeRecordCount().put(k, new BigDecimal(String.valueOf(v)).intValueExact()));
        Json.obj(x, "minRecomputePasses").forEach((k, v) ->
                exp.minRecomputePasses().put(Integer.parseInt(k), new BigDecimal(String.valueOf(v)).intValueExact()));

        for (Map<String, Object> e : Json.objList(x, "errors")) {
            exp.errors().add(new Expectations.ErrorExpectation(Json.str(e, "event"), Json.str(e, "code")));
        }
        for (Map<String, Object> t : Json.objList(x, "transactions")) {
            List<BigDecimal> amounts = new ArrayList<>();
            Object raw = t.get("amounts");
            if (raw instanceof List<?> list) {
                for (Object o : list) amounts.add(new BigDecimal(String.valueOf(o)));
            }
            exp.transactions().add(new Expectations.TransactionExpectation(
                    Json.str(t, "account"), Json.intVal(t, "day", 0), Json.str(t, "type"), List.copyOf(amounts)));
        }
        return exp;
    }

    // ----------------------------------------------------------------- write

    public static void writeAll(List<EventStream> streams, Path dir) {
        try {
            Files.createDirectories(dir);
            for (EventStream s : streams) {
                write(s, dir.resolve(s.id() + ".json"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write event streams to " + dir, e);
        }
    }

    public static void write(EventStream stream, Path file) {
        try {
            Files.writeString(file, Json.write(toJson(stream)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write event stream " + file, e);
        }
    }

    public static Map<String, Object> toJson(EventStream stream) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", stream.id());
        root.put("name", stream.name());
        root.put("description", stream.description());
        root.put("config", configToJson(stream.config()));

        List<Object> accounts = new ArrayList<>();
        for (AccountSpec a : stream.accounts()) {
            // LinkedHashMap, not Map.of: the serialized key order must be stable across runs.
            Map<String, Object> account = new LinkedHashMap<>();
            account.put("id", a.id());
            account.put("currency", a.currency().name());
            accounts.add(account);
        }
        root.put("accounts", accounts);

        List<Object> events = new ArrayList<>();
        for (LedgerEvent e : stream.events()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", e.label());
            m.put("day", BigDecimal.valueOf(e.postingDay()));
            m.put("valueDay", BigDecimal.valueOf(e.valueDay()));
            m.put("account", e.accountId());
            m.put("type", e.type().name());
            if (e.amount() != null) m.put("amount", e.amount().toPlainString());
            if (e.authId() != null) m.put("authId", e.authId());
            if (e.targetLabel() != null) m.put("target", e.targetLabel());
            if (e.installments() > 1) m.put("installments", BigDecimal.valueOf(e.installments()));
            if (e.note() != null) m.put("note", e.note());
            events.add(m);
        }
        root.put("events", events);
        root.put("expectations", expectationsToJson(stream.expectations()));
        return root;
    }

    private static Map<String, Object> configToJson(LedgerConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("weekStartDate", c.weekStartDate().toString());
        m.put("windowDays", BigDecimal.valueOf(c.windowDays()));
        m.put("capitalizationDay", BigDecimal.valueOf(c.capitalizationDay()));
        m.put("dailyInterestRate", c.dailyInterestRate().toPlainString());
        m.put("storeRounding", c.storeRounding().name());
        m.put("accrualRounding", c.accrualRounding().name());
        m.put("installmentRounding", c.installmentRounding().name());
        m.put("capitalizationExcludesOwnDay", c.capitalizationExcludesOwnDay());
        m.put("overdraftAssessmentExcludesOwnDayFee", c.overdraftAssessmentExcludesOwnDayFee());
        m.put("overdraftFeeOnDaysWithoutMovement", c.overdraftFeeOnDaysWithoutMovement());
        m.put("discardAccrualRemainder", c.discardAccrualRemainder());
        m.put("maxRecomputePasses", BigDecimal.valueOf(c.maxRecomputePasses()));
        m.put("maxRecalculationCycles", BigDecimal.valueOf(c.maxRecalculationCycles()));
        Map<String, Object> fees = new LinkedHashMap<>();
        for (Currency cur : Currency.values()) {
            fees.put(cur.name(), c.overdraftFeeFor(cur).toPlainString());
        }
        m.put("overdraftFees", fees);
        return m;
    }

    private static Map<String, Object> expectationsToJson(Expectations exp) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> snapshots = new ArrayList<>();
        for (Expectations.Snapshot s : exp.snapshots()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("afterDay", BigDecimal.valueOf(s.afterDay()));
            sm.put("account", s.accountId());
            List<Object> rows = new ArrayList<>();
            for (Expectations.Row r : s.rows()) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("day", BigDecimal.valueOf(r.day()));
                put(rm, "assessmentBalance", r.assessmentBalance());
                put(rm, "closing", r.closing());
                put(rm, "closingExInterest", r.closingExInterest());
                put(rm, "holds", r.holds());
                put(rm, "available", r.available());
                put(rm, "accrual", r.accrual());
                if (r.overdraft() != null) rm.put("overdraft", r.overdraft());
                rows.add(rm);
            }
            sm.put("rows", rows);
            snapshots.add(sm);
        }
        if (!snapshots.isEmpty()) m.put("snapshots", snapshots);

        if (!exp.finalInterest().isEmpty()) m.put("finalInterest", plain(exp.finalInterest()));
        if (!exp.authStatus().isEmpty()) m.put("authStatus", new LinkedHashMap<String, Object>(exp.authStatus()));
        if (!exp.netOverdraftFees().isEmpty()) m.put("netOverdraftFees", plain(exp.netOverdraftFees()));
        if (!exp.overdraftFeeRecordCount().isEmpty()) {
            Map<String, Object> counts = new LinkedHashMap<>();
            exp.overdraftFeeRecordCount().forEach((k, v) -> counts.put(k, BigDecimal.valueOf(v)));
            m.put("overdraftFeeRecordCount", counts);
        }
        if (!exp.minRecomputePasses().isEmpty()) {
            Map<String, Object> passes = new LinkedHashMap<>();
            exp.minRecomputePasses().forEach((k, v) -> passes.put(String.valueOf(k), BigDecimal.valueOf(v)));
            m.put("minRecomputePasses", passes);
        }
        if (!exp.errors().isEmpty()) {
            List<Object> errors = new ArrayList<>();
            for (Expectations.ErrorExpectation e : exp.errors()) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("event", e.eventLabel());
                em.put("code", e.code());
                errors.add(em);
            }
            m.put("errors", errors);
        }
        if (!exp.transactions().isEmpty()) {
            List<Object> txns = new ArrayList<>();
            for (Expectations.TransactionExpectation t : exp.transactions()) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("account", t.accountId());
                tm.put("day", BigDecimal.valueOf(t.day()));
                tm.put("type", t.type());
                tm.put("amounts", t.amounts().stream().map(BigDecimal::toPlainString).map(o -> (Object) o).toList());
                txns.add(tm);
            }
            m.put("transactions", txns);
        }
        return m;
    }

    private static void put(Map<String, Object> m, String key, BigDecimal value) {
        if (value != null) m.put(key, value.toPlainString());
    }

    private static Map<String, Object> plain(Map<String, BigDecimal> src) {
        Map<String, Object> m = new LinkedHashMap<>();
        src.forEach((k, v) -> m.put(k, v.toPlainString()));
        return m;
    }
}
