# NUMBERS.md

Every bare numeric constant in the production code (`src/main/java`), where it lives, where
it came from, and — for the ones I actually chose rather than copied from `Notes.md` or this
conversation — why that value and not half it. Booleans and enum choices (which rounding
mode, which way an ambiguity resolves) are argued in [`AMBIGUITIES.md`](AMBIGUITIES.md)
instead; this file is about magnitudes.

## Given by the spec, not chosen

These aren't really mine to defend - Notes.md states them directly - but "why not half it"
is still a fair question, so here's what half would actually break.

### `windowDays = 6` — `LedgerConfig.Builder`
**Source:** "run an event stream ... across a 6-day window" (Notes.md, problem statement).
**Why not half it (3):** the baseline stream's own instructions run through day 6 (E9 on
day 6) and ACC001's worked interest total sums days 1–5. A 3-day window would truncate the
stream Notes.md itself wrote out before it finishes - E5 through E9 all land on days 4-6.

### `capitalizationDay = 6` — `LedgerConfig.Builder`
**Source:** "total accrual only calculated and credited at end of 6 days" (Notes.md). Tied
to `windowDays` for the shipped streams, but is its own field because iteration 2 sets
`windowDays=8` while leaving `capitalizationDay=6` — the two are independent by design.
**Why not half it (3):** day 3 is mid-week in every shipped stream; capitalizing there
would credit interest on an accrual history that keeps changing for three more days,
which is exactly the circularity `capitalizationExcludesOwnDay` exists to avoid (see
`AMBIGUITIES.md`) - halving this constant would manufacture the very problem that flag
solves.

### `dailyInterestRate = 0.0004` (0.04%) — `LedgerConfig.Builder`
**Source:** "0.04% interest on closing balance" (Notes.md), literally `0.04 / 100`.
**Why not half it (0.0002):** every worked figure in Notes.md's own walkthrough (day 1's
0.10 AED on a 250.00 balance, day 4's 0.18 on 465.00) is `closing × 0.0004`, rounded. Half
the rate reproduces none of them - 250.00 × 0.0002 = 0.05, not the stated 0.10.

### `AED.precision = 2`, `AED.defaultOverdraftFee = 25.00` — `Currency`
**Source:** "AED is two decimal places" and "25AED" (Notes.md), verbatim.
**Why not half it:** halving the fee to 12.50 contradicts the number Notes.md prints
outright. Halving the precision isn't meaningful - 1 decimal place isn't a valid currency
minor unit, and every fils-level figure in the worked example (250.00, 465.82) needs the
second digit.

### `BHD.precision = 3` — `Currency`
**Source:** "BHD is three decimal places" (Notes.md).
**Why not half it (1 or 2):** the installment example is the proof: 10 BHD ÷ 3 must land on
3.333 with a 0.001 remainder (Notes.md's own figures, typo aside - see `REJECTED.md` R3).
At 2 decimal places the remainder would be 0.01 instead and BHD would round identically to
AED, defeating the entire point of the example.

## Genuinely chosen

### `BHD.defaultOverdraftFee = 2.500` — `Currency`
**Source:** Mentioned to calculate BHD approx 10x AED
**Why this value:** AED 25 is roughly 1% of the 2,400 AED that moves through the baseline
week (1200 credited, 950+620 debited). 2.500 BHD is the same proportion of a comparable BHD
week's scale in iteration 4/5 (10-30 BHD moving), keeping the fee "a real but not
crushing" bite in both currencies rather than picking a number that swamps one currency's
example and rounds to noise in the other.
**Why not half it (1.250):** at 1.250, iteration 4's BHD002 (closes at -4.000 before the
fee) would still go negative and the fee would still land the same way - the qualitative
behavior tested doesn't change. What would change is BHD002's day-4 closing (-5.250
instead of -6.500) and every downstream figure, for no reason grounded in the spec. 

### `maxRecomputePasses = 16` — `LedgerConfig.Builder`
**Source:** mine - a safety cap on one `reconcile()` call's fixed-point loop
(`ARCHITECTURE.md` §2.4), not a business rule.
**Why this value:** the highest pass count any shipped stream actually reaches is **4**
(iteration 1's day 6, verified by running all five streams and reading the reports). 16 is
4x that observed maximum - enough headroom for a stream with two or three more layers of
back-dated cascade than anything shipped, while still turning a genuine infinite loop (a
future rule that breaks the monotonicity argument in §2.4) into a fast, visible
`RECOMPUTE_DID_NOT_CONVERGE` error rather than a hang that eats CPU for a long time first.
**Why not half it (8):** 8 would still cover every shipped stream today (4 ≤ 8) - this one
would *not* break anything currently running. The reason it isn't 8 is margin for
streams nobody has written yet: each additional independent back-dated event into the same
window can add roughly one more pass (see the day-2/5/7/8 cascade in
`RecalculationCycleLimitTest`), and `maxRecalculationCycles` already permits up to 3 such
events landing on one day *per account* - across several accounts and several disturbed
days in one stream, 8 has noticeably less room to spare than 16 before a legitimate,
still-converging stream would be mistaken for a runaway one.

### `maxRecalculationCycles = 3` — `LedgerConfig.Builder`
**Source:** given directly in this conversation ("This need to be regulated with max 3
cycles") - not something I picked, but the reasoning for why 3 is a sound choice, not just
an arbitrary one, is worth recording.
**Why this value:** 1 would forbid any back-dated correction at all after a day's first
close - too strict, since Notes.md's own non-negotiables *require* back-dated corrections
to work (the day-2 debit landing on day 5, its reversal on day 6). 2 would allow exactly
one correction and no correction-of-a-correction - but "reverse that transaction and
readjust the accounts" is itself a second touch on top of whatever it's correcting, so 2
leaves no room to fix a mistaken fix. 3 is the smallest number that comfortably covers
"an entry, a reversal of it, and one more legitimate correction" - the shape of the
cascade example itself - while still refusing a day that keeps getting rewritten
indefinitely.
**Why not half it (1, rounding down from 1.5):** every shipped stream that back-dates at
all touches its disturbed day exactly twice (iteration 1's day 2, iteration 2's day 3,
iteration 5's day 1 - each gets one entry and one reversal, landing at cycle 3 including
the native close). A limit of 1 would reject the *second* touch - the reversal - which is
literally required by "reverse that transaction and readjust the accounts." It would make
correcting a back-dated mistake impossible on the first attempt, defeating the feature
this limit is supposed to bound, not enable.

### `rawInterestAccrual` internal scale = `12` decimal places — `LedgerEngine.rebuildProjections`
**Source:** mine - an internal-only buffer, never stored or shown to a user; only feeds
`discardAccrualRemainder`'s rejected-reading comparison (`AMBIGUITIES.md`) and
`AmbiguityChoicesTest`.
**Why this value:** `closing × dailyInterestRate` multiplies a 2–3 decimal-place currency
amount by a 4 decimal-place rate (0.0004), which needs at most 6–7 decimal places to
represent exactly with no rounding at all. 12 is a deliberately generous buffer above that
- room to sum several days' worth of such products with zero accumulated rounding error,
so this figure can honestly claim to be the *un-rounded* truth the rounded, stored
accrual is compared against.
**Why not half it (6):** 6 is close to the actual precision the underlying multiplication
needs, which is exactly the risk - it leaves no slack for the *sum* of several days'
figures to occasionally need a 7th digit, and this number exists specifically to be
trustworthy as "more precise than anything derived from it," not merely "precise enough
today."

