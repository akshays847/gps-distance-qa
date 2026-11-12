# directive.md: GPS distance billing: corrected calculation and regression lock

> **SYNTHETIC DATA ONLY.** Fictional assessment case (vehicle advertising, pay per km in a zone). Every result below was produced by the code in this repo and can be reproduced with one command. Nothing here comes from a real company, user or incident.

## 1. Objective

Make the per-driver, per-day billable distance correct under the campaign rules, prove it against an independent ground truth, and add a regression check that fails on the old behaviour and passes on the new.

## 2. Scope

**In scope:** one campaign zone (rectangle), one road (polyline), 5 drivers, 2 report days, 3,070 synthetic pings with injected faults; the old calculation (v1), a corrected reference implementation (v2), a QA tool, and regression tests.
**Out of scope:** real map-matching, gap handling, arbitrary zone polygons, restating reports already issued, thresholds tuned on real data. See *Not solved* below.

## 3. Requirements (the rules, as implemented)

| # | Rule | Implementation (`DistanceEngine.java`) | Regression scenario |
|---|---|---|---|
| R1 | Ping >100 m off the road path is noise | Distance from ping to road polyline > 100 m → reject | S1 |
| R2 | Ping implying >150 km/h is noise | Speed vs the **last valid** ping > 150 km/h → reject | S2 |
| R3 | Duplicates (same driver + timestamp) count once | First received is kept | S3 |
| R4 | Late / out-of-order pings placed by own timestamp | Stable sort by `ts_utc` after dedupe | S4 |
| R5 | Daily report cut at 00:00 UTC+9 | Segments crossing local midnight are split in proportion to time | S5 |
| R6 | Distance counts only inside the zone | Segments clipped at the zone edge (Liang-Barsky) | S6 |
| R7 | *(found by the tool)* Correct distance | Haversine instead of flat degrees | S7 |

Pipeline order: dedupe → sort → off-road → speed → sum segments (haversine, clipped, split by local day).

## 4. Completion criteria

| Criterion | Target | Result |
|---|---|---|
| v2 vs true km, every driver-day | ≤ 50 m | **8.4 m** worst ✅ |
| v2 vs true km, each driver's total | ≤ 10 m | **2.2 m** worst ✅ |
| Faulty pings rejected (answer key) | all 156, no false positives | **156 / 156, 0 false positives** ✅ |
| Late pings kept and re-ordered | all 121 | **121 / 121** ✅ |
| Regression scenarios | v1 FAIL, v2 PASS, v2-minus-fix FAIL | **7 / 7** ✅ |
| Engine with no fixes reproduces v1 exactly | identical | **identical** ✅ |

## 5. How to run

```
run.bat        (Windows)      ./run.sh      (Mac/Linux)      needs Java 17+ and Maven
```
This runs the tests, regenerates the data and writes `reports/qa_report.html`. The same runs on every push via GitHub Actions.

## 6. How AI was used: what it produced, what I checked, changed or rejected

**AI tool:** Claude (Anthropic). It wrote the code, the synthetic data generator and the first drafts of these documents.

**What existed before:** nothing. Everything was built for this Quest.

**What the AI got wrong, and how it was caught.** These are real events from the build; the full log is in `AI_LOG.md`.

| # | What the AI produced | How it was caught | Correction |
|---|---|---|---|
| 1 | Late-ping check counted **182** late pings | The tool's count didn't match the answer key (121 injected) | Re-sent duplicates arriving after the next ping were counted as "late" too. They're now excluded (they're C1's job) → 121 |
| 2 | Zone-boundary check reported **+0.629 km**, while the root-cause waterfall said **−0.101 km** for the same fix | Two numbers in the same report disagreed | The check ran on data that still had speed spikes in it. It now uses the fully cleaned track → +0.101 km, matching |
| 3 | "Km moved between days" added up changes **across different drivers** | The zone-clipping step showed km "moved between days", which clipping cannot do | Now computed per driver, then summed |
| 4 | A test asserted each driver's total is **exact to 1 m** | The test failed: D01 was off by 2.2 m | Not a bug in v2. When a noisy ping is dropped, the real reading at that moment is lost. Tolerance set to 10 m, with the reason written in the test |
| 5 | I ran the real mvn test on my laptop (Java 25, Maven 3.9.16): 12 tests, 0 failures.
| 6 | Told me to break the off-road rule by editing Config.java and said S1 would fail | I checked: S1 uses its own test map | Broke the rule in DistanceEngine.java instead; S1 failed as expected |

**Design findings made during the build:**
- **The day split for D05 is 8 m off** while its total is exact. Traced to ping P02754: the reading at exactly local midnight was GPS noise and was rejected. v2 splits the 20 s gap assuming constant speed. That's correct per the rules, and it's the limit of what the data allows.
- **Removing only the dedupe fix does not break the duplicates test.** The speed filter also rejects a moved duplicate (22 m in 0 s = infinite speed), and exact copies add 0 m. Scenario S3 therefore guards both fixes. Duplicates are still removed explicitly, so the rule doesn't depend on a noise threshold.
- **The speed rule was mutation-tested:** changing it to "compare with previous raw ping" makes S2 fail (`good ping A-6 was rejected`).

✏️ **What I checked myself:
- [ ] Ran `run.bat` / `run.sh` on my machine; all tests green
- [ ] Opened `qa_report.html` and spot-checked evidence ping ids in `pings_SYNTHETIC.csv`
- [ ] Broke a fix on purpose and watched the matching test fail

## 7. Not solved / known limits

1. **Gaps** (phone off): a straight line is drawn across them. There are no in-zone gaps in this data.
2. **First ping of a trip is noise:** it becomes the speed anchor and good pings after it could be rejected. Untested; the generator never faults the first two pings.
3. **Day split when the ping at midnight is lost:** constant speed is assumed (worst case here 8 m).
4. **Road and zone are simplified:** a polyline and a rectangle. No real map-matching.
5. **Thresholds are hard edges:** a real 148 km/h or a tunnel 95 m from the mapped line are not treated specially.
6. **Late pings after a report has been issued** need a restatement process. That's out of scope for the calculation.
7. **Ground truth** shares the haversine function with v2. It's independent of the clipping and day-split maths (it uses fine sampling), but not of the distance formula.

## 8. Effort

Actual hours: 8

---

## Appendix: results and handoff

| Item | Link |
|---|---|
| Repository | `https://github.com/akshays847/gps-distance-qa` |
| **QA tool** (checker + report) | [`src/main/java/qa/QaChecker.java`](https://github.com/akshays847/gps-distance-qa/blob/main/src/main/java/qa/QaChecker.java) |
| Report (open in browser) | `reports/qa_report.html`, or download the `qa-report` artifact from the latest Actions run |
| **Dataset** (SYNTHETIC) | [`data/pings_SYNTHETIC.csv`](https://github.com/akshays847/gps-distance-qa/blob/main/data/pings_SYNTHETIC.csv) · [answer key](https://github.com/akshays847/gps-distance-qa/blob/main/data/fault_labels_SYNTHETIC.csv) · [true km](https://github.com/akshays847/gps-distance-qa/blob/main/data/ground_truth_SYNTHETIC.csv) · [generator](https://github.com/akshays847/gps-distance-qa/blob/main/src/main/java/qa/DataGenerator.java) |
| Old calculation (v1) | [`LegacyCalculator.java`](https://github.com/akshays847/gps-distance-qa/blob/main/src/main/java/qa/LegacyCalculator.java) |
| **Fix** (v2) | [`DistanceEngine.java`](https://github.com/akshays847/gps-distance-qa/blob/main/src/main/java/qa/DistanceEngine.java) |
| **Regression check** | [`RegressionTest.java`](https://github.com/akshays847/gps-distance-qa/blob/main/src/test/java/qa/RegressionTest.java) · [`Scenarios.java`](https://github.com/akshays847/gps-distance-qa/blob/main/src/main/java/qa/Scenarios.java) · [`DatasetTest.java`](https://github.com/akshays847/gps-distance-qa/blob/main/src/test/java/qa/DatasetTest.java) |
| **Regression results** (CI run) | https://github.com/akshays847/gps-distance-qa/actions
| Before/after CSV | [`reports/before_after_SYNTHETIC.csv`](https://github.com/akshays847/gps-distance-qa/blob/main/reports/before_after_SYNTHETIC.csv) |
| AI log | [`AI_LOG.md`](https://github.com/akshays847/gps-distance-qa/blob/main/AI_LOG.md) |

### A. Regression matrix (generated by the tool)

| ID | Rule | v1 (old) | v2 (fixed) | v2 minus that fix |
|---|---|---|---|---|
| S1 | Noise: >100 m off road | FAIL | PASS | FAIL |
| S2 | Noise: >150 km/h (good ping after spike kept) | FAIL | PASS | FAIL |
| S3 | Duplicates count once | FAIL | PASS | FAIL |
| S4 | Late pings placed by own timestamp | FAIL | PASS | FAIL |
| S5 | Day cut at 00:00 UTC+9 | FAIL | PASS | FAIL |
| S6 | Distance only inside the zone | FAIL | PASS | FAIL |
| S7 | Correct distance formula (extra) | FAIL | PASS | FAIL |

### B. Root cause: km attributed to each bug (fixes switched on one at a time)

| Step | Total km | Change | Km moved to correct day |
|---|---:|---:|---:|
| v1 (no fixes) | 487.975 | +0.000 | 0.000 |
| + Duplicates counted once | 476.680 | -11.295 | 0.000 |
| + Late / out-of-order pings placed by own timestamp | 203.361 | -273.319 | 0.000 |
| + GPS noise: >100 m off road ignored | 196.755 | -6.606 | 0.000 |
| + GPS noise: >150 km/h ignored | 166.230 | -30.524 | 0.000 |
| + Correct distance formula | 149.042 | -17.188 | 0.000 |
| + Only distance inside the zone | 148.942 | -0.101 | 0.163 |
| + Day cut at 00:00 UTC+9 | 148.942 | +0.000 | 35.592 |

### C. Distance before and after, per driver per day

v1 books by **UTC** date, so its rows are UTC days (that is why v1 has km on 2026-11-01). v2 and the true km use **report days (UTC+9)**.

| Driver | Day | v1 old km | v2 fixed km | True km | v2 − true (m) |
|---|---|---:|---:|---:|---:|
| D01 | 2026-11-01 | 6.904 | 0.000 | 0.000 | +0.0 |
| D01 | 2026-11-02 | 61.527 | 17.308 | 17.309 | -0.9 |
| D01 | 2026-11-03 | 33.383 | 12.536 | 12.537 | -1.3 |
| D02 | 2026-11-01 | 21.141 | 0.000 | 0.000 | +0.0 |
| D02 | 2026-11-02 | 63.502 | 17.871 | 17.871 | -0.2 |
| D02 | 2026-11-03 | 12.992 | 11.905 | 11.905 | -0.3 |
| D03 | 2026-11-01 | 20.436 | 0.000 | 0.000 | +0.0 |
| D03 | 2026-11-02 | 82.021 | 15.207 | 15.207 | -0.4 |
| D03 | 2026-11-03 | 33.387 | 14.591 | 14.591 | +0.2 |
| D04 | 2026-11-01 | 7.472 | 0.000 | 0.000 | +0.0 |
| D04 | 2026-11-02 | 53.103 | 17.841 | 17.840 | +0.5 |
| D04 | 2026-11-03 | 12.089 | 11.887 | 11.887 | +0.0 |
| D05 | 2026-11-01 | 13.813 | 0.000 | 0.000 | +0.0 |
| D05 | 2026-11-02 | 50.210 | 15.388 | 15.380 | +8.1 |
| D05 | 2026-11-03 | 15.994 | 14.408 | 14.416 | -8.4 |
| **Total** | | **487.975** | **148.942** | **148.945** | |
