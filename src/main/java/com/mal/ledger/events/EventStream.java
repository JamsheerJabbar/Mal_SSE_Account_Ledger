package com.mal.ledger.events;

import com.mal.ledger.engine.LedgerConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** One independently executable test iteration: config + accounts + events + expectations. */
public final class EventStream {

    private final String id;
    private final String name;
    private final String description;
    private final LedgerConfig config;
    private final List<AccountSpec> accounts;
    private final List<LedgerEvent> events;
    private final Expectations expectations;

    public EventStream(String id, String name, String description, LedgerConfig config,
                       List<AccountSpec> accounts, List<LedgerEvent> events,
                       Expectations expectations) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.config = config;
        this.accounts = List.copyOf(accounts);
        this.events = List.copyOf(events);
        this.expectations = expectations;
    }

    public String id() { return id; }
    public String name() { return name; }
    public String description() { return description; }
    public LedgerConfig config() { return config; }
    public List<AccountSpec> accounts() { return accounts; }
    public List<LedgerEvent> events() { return events; }
    public Expectations expectations() { return expectations; }

    /** Events arriving on a given day, in declaration order (ACC001 first, then ACC002). */
    public List<LedgerEvent> eventsOn(int day) {
        List<LedgerEvent> out = new ArrayList<>();
        for (LedgerEvent e : events) {
            if (e.postingDay() == day) out.add(e);
        }
        return out;
    }

    public int lastEventDay() {
        return events.stream().mapToInt(LedgerEvent::postingDay).max().orElse(config.windowDays());
    }

    /** Days the runner will walk: the declared window, extended if events land after it. */
    public int lastProcessedDay() {
        return Math.max(config.windowDays(), lastEventDay());
    }

    public LedgerEvent eventByLabel(String label) {
        return events.stream()
                .filter(e -> e.label().equals(label))
                .findFirst()
                .orElse(null);
    }

    public List<LedgerEvent> eventsSorted() {
        List<LedgerEvent> copy = new ArrayList<>(events);
        copy.sort(Comparator.comparingInt(LedgerEvent::postingDay));
        return copy;
    }

    @Override
    public String toString() {
        return id + " - " + name;
    }
}
