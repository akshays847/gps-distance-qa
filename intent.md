# intent.md: GPS distance billing QA

> **Data:** all numbers below come from a SYNTHETIC dataset (3,070 pings, 5 drivers, 2 report days) generated for this Quest. No real drivers, advertisers, locations or employer data.


## 1. The problem in one line

The reference calculation bills the advertiser **487.97 km**; the true in-zone distance is **148.94 km**. That is a 3.3× overcharge, and 35.6 km are booked on the wrong day.

## 2. Problems found, ranked by impact

Impact was measured by the tool. It switches each fix on one at a time and measures how the bill changes (root-cause waterfall, `reports/root_cause_waterfall.csv`). Attribution depends on the order the fixes are applied; I used pipeline order.

| Rank | Problem | Bill impact | Who is hurt | How I found it |
|---|---|---|---|---|
| 1 | **Late / out-of-order pings processed in arrival order** | **−273.3 km** (56% of the old bill) | Advertiser overpays. Drivers paid per km are overpaid, which becomes a pay cut when it's fixed | Check C2: per driver, flags any ping whose timestamp is older than one already received. 121 pings, up to 39 min late. Evidence shows v1 drawing 4–11 km lines back to a late ping and forward again |
| 2 | **GPS noise not filtered** (impossible speed + off road) | **−37.1 km** (−30.5 speed, −6.6 off-road) | Advertiser overpays | C3: distance from each ping to the road polyline (33 pings over 100 m, worst 484 m). C4: implied speed vs the last valid ping (31 pings, up to ~590 km/h). Map in the report |
| 3 | **Flat-degree distance formula** | **−17.2 km** (v1 +11.6% on in-zone segments) | Advertiser overpays | Not in the brief. Found by comparing v1's segment lengths with haversine on the same segments (C7). v1 treats 1° of longitude as 111 km; at 37.5°N it is 88 km |
| 4 | **Duplicates counted twice** | **−11.3 km** | Advertiser overpays | C1: group by driver + timestamp. 92 extra rows (39 exact, 53 with moved coordinates) |
| 5 | **Day cut at UTC midnight instead of 00:00 UTC+9** | **0 km on the total, but 35.6 km on the wrong day** | Both. Daily invoices and daily driver pay are wrong. v1 even produces a "2026-11-01" day that does not exist in local time | C5: compare UTC date with UTC+9 date for every ping (1,468 differ: all 00:00–09:00 local). 3 in-zone segments cross local midnight |
| 6 | **Zone boundary**: whole segment counted if its start is inside | **−0.1 km** | Small either way; it's systematic, so it grows with more zone edges | C6: 50 segments cross the zone edge; compared "start inside" with clipped length |

My ranking reasoning: I ranked by how many km each bug adds to the advertiser’s bill. Late pings are biggest. The day-cut doesn’t change the total, but every daily report is wrong, so I’d fix it early for trust.

**Caveat:** the sizes come from the fault rates I chose for the synthetic data (4% late, 2.3% noise, 3% duplicates). With real data the order could change. The tool would show that immediately.

## 3. What I chose to solve first, and why

**Late / out-of-order pings (rank 1).** It's the largest error by far. It also has to come first technically: the noise filter judges each ping by its speed from the previous ping, so on unsorted data good pings look like 500 km/h jumps. Dedupe and sort are prerequisites for everything else.

The other five rules were fixed in the same corrected implementation, because the regression check has to cover noise, duplicates, late pings, the UTC+9 cut and the zone boundary.

## 4. Decisions where the brief was ambiguous

| Question | Decision | Why |
|---|---|---|
| Speed is measured against which ping? | The **last valid** ping | Comparing with the previous raw ping makes one spike also reject the good ping after it. The tool shows this would discard 31 good pings (C4). Regression test S2 guards it |
| Which duplicate is kept? | The **first one received** | Deterministic. The moved duplicates are 8–25 m apart, so either choice changes km by metres |
| A segment crossing the zone edge | **Clip** at the edge, count only the inside part | The brief says distance counts only *while* inside |
| A segment crossing local midnight | **Split** at 00:00 UTC+9, in proportion to time | Same reasoning, applied to time |
| Off-road check order | Off-road filter **before** speed filter | So speed is measured from an on-road position |
| Gaps (phone off) | **Not handled** | Not in the brief. Listed under "left out" |

## 5. What I left out

- **Gap handling**: a straight line is drawn across any gap between pings. The data has no gaps inside the zone, so this has no effect here.
- **Real map-matching**: the road is a synthetic polyline, and "off road" is the distance to it.
- **Zone shape**: a rectangle, not an arbitrary polygon.
- **Restating reports already sent**: a ping that arrives after a day's report has been issued needs a correction process. That's a process question, not a calculation one.
- **The first ping of a trip being noise**: if it is, the speed filter compares against a bad anchor. My synthetic data never faults the first two pings, so this is untested.
- **Threshold edge cases**: a real 148 km/h on a highway, or a car in a tunnel 90 m from the mapped road line.

## 6. What "done" means

1. v2 matches the true km for every driver-day within 50 m (actual worst: **8.4 m**), and each driver's two-day total within 10 m (actual worst: **2.2 m**).
2. v2 rejects exactly the 156 faulted pings (33 off-road + 31 spikes + 92 duplicates), keeps all 121 late pings, and has 0 false positives.
3. Every regression scenario FAILS on v1, PASSES on v2, and FAILS when its own fix is removed.
