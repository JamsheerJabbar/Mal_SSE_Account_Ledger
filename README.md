# MAL Account Ledger

In-memory, append-only account ledger for the problem in [`Notes.md`](Notes.md): run an
event stream day by day across a 6-day window and print, for each day, the closing ledger
balance, the fee assessments, the auth states and the errors.

No persistence, no database, no UI. Java 21, Gradle, zero third-party production
dependencies (the JSON codec is in `com.mal.ledger.io.Json`).

## Quick start

```bash
gradle generateStreams                              # (re)write event-streams/*.json
gradle runStreams                                   # run all five, print the day reports
gradle runStreams -Pstream=iteration-1-baseline     # run just one
gradle test                                         # 75 tests
gradle test --tests '*Iteration2*'                  # one iteration on its own
```

## The five iterations

Each file in `event-streams/` is a complete, independently executable iteration: its own
accounts, its own config, its own instructions and its own expectations. Nothing is shared.

| File | What it pins down |
|---|---|
| `iteration-1-baseline.json` | The stream written out in Notes.md, verbatim, with the day-by-day figures it states. |
| `iteration-2-retro-cascade.json` | An 8-day window: a 2000 AED debit back-dated into day 3 arrives on **day 7**, after interest was already credited. Four fees raised, interest reversed and re-credited, then day 8 unwinds it all. |
| `iteration-3-auth-holds.json` | Auth and hold lifecycle in isolation — approvals at positive and at exactly zero headroom, and every settlement rejection path. |
| `iteration-4-precision.json` | Two currencies at two precisions, installment remainders, and balances small enough that the daily accrual rounds to zero every day. |
| `iteration-5-mixed-stress.json` | AED and BHD side by side, a hold live across a restatement, one fee reversed and one that stands. |

## Editing a stream

The JSON files are the source of truth — the test suite reads them at run time, so an edit
changes what runs with no recompilation. `//` and `/* */` comments are allowed.

```jsonc
{
  "id": "my-stream",                       // must match the file name
  "config": { "windowDays": 6, "capitalizationDay": 6, "weekStartDate": "2026-01-01" },
  "accounts": [ { "id": "X1", "currency": "AED" } ],
  "events": [
    { "label": "A", "day": 1, "valueDay": 1, "account": "X1", "type": "CREDIT", "amount": "1000" },
    { "label": "B", "day": 5, "valueDay": 2, "account": "X1", "type": "DEBIT",  "amount": "1500" },
    { "label": "C", "day": 6, "account": "X1", "type": "REVERSAL", "target": "B", "valueDay": 2 }
  ],
  "expectations": { "netOverdraftFees": { "X1": "0.00" } }   // optional; only what is present is asserted
}
```

Event types: `CREDIT`, `DEBIT`, `INSTALLMENT_CREDIT` (`"installments": 3`), `AUTH`
(`"authId"`), `SETTLEMENT` (`"authId"`), `REVERSAL` (`"target"`). `valueDay` defaults to
`day`; set it lower to back-date.

Drop a new `.json` file into `event-streams/` and `AllEventStreamsTest` picks it up as a new
test case automatically — no Java change. The five shipped files are also generated from
`EventStreamFactory`, so if you hand-edit one, either update the factory to match or delete
the `filesMatchTheGenerator` check.

## Design

Two structures do the work:

- **The journal** is append-only. Nothing is updated or deleted; a correction is a new
  `REVERSAL` / `*_REVERSAL` record referencing the record it undoes.
- **Daily accounts are a derived projection**, rebuilt from the journal on every
  reconciliation. They can never drift, and `AllEventStreamsTest` asserts they reconcile
  to the journal sum for every stream.

Reconciliation runs as a **fixed-point loop**: rebuild every day, assess the overdraft fee,
reconcile the interest credit, repeat until a pass appends nothing. That is the "two three
loops of calculation over a reversal on the 7th or 8th day" the notes warn about, made
explicit — the pass count is printed per day and asserted in iteration 2.

The loop terminates because **a day is judged on its balance before its own fee**. Applying
a fee can never flip a day back to solvent, so a single forward walk settles the week.
Earlier days' fees *do* count, which is why day 4 in the baseline closes at −180 and not −155.

## Resolved ambiguities

Every choice Notes.md flags is a config flag in `LedgerConfig`, and `AmbiguityChoicesTest`
runs the baseline **both ways** so the cost of each reading is visible.

| Flag | Chosen | Why | Other reading gives |
|---|---|---|---|
| `accrualRounding` | `DOWN` | Notes.md requires day 4 (465.00 × 0.0004 = 0.186) to accrue **0.18**. Also never credits interest the balance has not earned. | `HALF_UP` → 0.84 total, not 0.82 |
| `discardAccrualRemainder` | `false` | The capitalized total is exactly the sum of the stored daily accruals, so the credit always reconciles to its own audit trail. | `true` → 0.83, which the trail no longer explains |
| `capitalizationExcludesOwnDay` | `true` | The credit lands on day 6, so including day 6's own accrual is circular. Confirmed by the notes: ACC001 totals 0.82 (days 1–5), ACC002 totals 0.004 (day 5 only). | `false` → 1.00 and 0.008, contradicting both stated figures |
| `overdraftAssessmentExcludesOwnDayFee` | `true` | "Day 2 … before any fee assessed = −370". Makes assessment idempotent and self-correcting. | `false` → a day at +10 under a −25 fee stays "overdrawn" forever |
| `overdraftFeeOnDaysWithoutMovement` | `false` | **Notes.md contradicts itself here** — see below. | `true` → day 5 closes at −230 and three fees are raised |
| `installmentRounding` | `DOWN` | Every slice must be ≤ total/n; the remainder is posted as a `BALANCING_ADJUSTMENT` tagged `BUFFER_PAY`. | `HALF_UP` → 1.000 BHD / 7 would post 0.143 × 7 = 1.001, over the total |

### The one contradiction in Notes.md

The acceptance text says the overdraft "carries on to day4 **and day 5**" — three fees. The
worked figures say day 5 closes at **−205** and day 6 raises exactly two reversals, "for day2
and day4" — two fees. Both cannot hold: a day-5 fee would make day 5 close at −230.

We follow the figures (a day with no ledger movement of its own is not separately assessed;
it carries the prior balance forward) and ship the other reading behind
`overdraftFeeOnDaysWithoutMovement: true`, tested in `AmbiguityChoicesTest`. Either way the
day-6 reversal restores the same end state — the readings differ only mid-week.

## Notes.md acceptance criteria — the verdicts

`AcceptanceCriteriaTest` has one test per criterion.

| Criterion | Verdict | Why |
|---|---|---|
| Day 2 is −370, so **exactly one** fee is assessed | **Wrong** | The −370 is right. The conclusion is not: the fee takes day 2 to −395, day 3's +400 leaves only 5.00, and day 4 closes at −180 and is charged again. Two fees. |
| The day-4 settlement of authA should be accepted | **Right** | The ledger stood at 650 (≥ 185) when it arrived and 185 is within the 200 hold. The back-dated debit that later drags day 4 negative had not been instructed yet and cannot retroactively refuse it. |
| A settlement quoting an unknown auth id must be refused | **Right** | Accepting it would let a settlement instruction alone move money with no prior authorization. Refused, recorded, no funds move. Sibling cases — rejected auth, already-settled auth, settlement over the hold — are in iteration 3. |
| Holds reduce available balance but not ledger balance | **Right as a rule, moot in the baseline** | The rule holds (iteration 3 proves it on an approved hold), but authB never becomes a hold: available balance was −205 when authorization was requested. |
| After E9 all balances and fees return to pre-E7 values | **Right** | Day 6 closes at 465.00 before interest — exactly where day 4 stood before E7 — and both fees reverse, because with the debit gone there is nothing to charge for. E8's refusal is *not* undone: it was correct on the information available that day. |
| The three BHD installments should be 3.334 | **Wrong** | 3.334 × 3 = 10.002, more than was instructed. Slices round down to 3.333; the 0.001 remainder is posted as a `BUFFER_PAY` balancing entry. (The notes' own "0.333" is a typo for 3.333 — 10/3 is 3.333, not 0.333.) |
| If rounded daily accruals do not sum to the capitalized total, discard the remainder | **Wrong** | The capitalized total is the rounded sum of the stored daily accruals. Nothing is discarded, so the credit always reconciles to the accrual history it came from. |

## Layout

```
event-streams/                     the five iterations, editable JSON
src/main/java/com/mal/ledger/
  domain/       Account, Transaction, Auth, DailyAccount, Currency, Money
  engine/       LedgerEngine (the whole ledger), LedgerConfig, ErrorCode
  events/       LedgerEvent, EventStream, EventStreamFactory (generates the streams)
  io/           Json (own codec), EventStreamStore (read/write the JSON)
  report/       DayReport, ReportPrinter
  runner/       StreamRunner (walks a stream day by day), Main (CLI)
src/test/java/com/mal/ledger/
  Iteration1..5*Test            one class per iteration, independently runnable
  AllEventStreamsTest           parameterized over every file in event-streams/
  AcceptanceCriteriaTest        the Notes.md verdicts above
  AmbiguityChoicesTest          each choice run both ways
  EventStreamEditingTest        round-trip and edit-takes-effect
```
