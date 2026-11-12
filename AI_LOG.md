# AI log

I used Claude (AI) to write the code. This file lists the mistakes the AI made, how I found them, and what was changed. Everything here really happened.

## Mistakes the AI made

| # | What went wrong | How it was found | What was changed |
|---|---|---|---|
| 1 | The tool said there were **182 late pings**, but only **121** were put in the data. | I compared the tool's number with the answer key file (`fault_labels_SYNTHETIC.csv`). | Duplicate pings were being counted as late pings too. Now duplicates are skipped in the late-ping check. Result: 121, correct. |
| 2 | The zone-edge check said **+0.629 km**, but another table in the same report said **−0.101 km** for the same thing. | Two numbers in one report did not match. | The check was using data that still had bad GPS jumps in it. Now it uses fully cleaned data. Both numbers now say 0.101 km. |
| 3 | The "km moved to another day" number was wrong. It mixed up different drivers. | The zone fix showed km "moving between days", which is impossible: that fix does not touch dates. | It now calculates per driver first, then adds them up. |
| 4 | A test expected each driver's total to be correct **to the exact metre**. It failed: D01 was off by **2.2 m**. | The test failed when run. | Not a real bug. When a bad GPS ping is removed, the true position at that second is lost, so a tiny bit of distance (a few metres) is lost too. The test now allows 10 m, and the reason is written in the test. |
| 5 | The AI could not run the real tests on its own computer (it was blocked from downloading the test library). | Build error. | The AI checked the logic another way. **I then ran the real tests on my laptop (Java 25, Maven 3.9.16): all passed.** |
| 6 | Small text formatting mistake in the report (`D01 :` with an extra gap). | I saw it in the HTML report. | Fixed. |
| 7 | The AI told me to break the off-road rule by editing `Config.java`, and said test S1 would fail. **That was wrong**: S1 uses its own small test map, so it would still pass. | I checked which settings S1 actually uses. | I broke the rule in `DistanceEngine.java` instead. S1 failed as expected. |

## Extra checks (to make sure the AI's work is really correct)

- **Broke the speed rule on purpose:** changed it to compare with the previous ping instead of the last good ping. Test S2 failed straight away. Changed it back. This proves S2 really protects that rule.
- **Duplicates test:** removing only the duplicate fix did **not** make the test fail, because the speed filter also catches moved duplicates (a 22 m jump in 0 seconds is "infinite speed"). So the duplicates test now protects both fixes.
- **Driver D05 is 8 m off between two days** (its total is exact). Reason: the GPS ping exactly at midnight was a bad ping and was removed, so the tool has to guess how the distance splits across midnight. Listed as "not solved".
- **The fixed code with every fix switched off gives exactly the same km as the old code.** This proves the root-cause table is fair.

## My own checks

<!-- Add only the checks you really did. Example rows below; delete any you did not do. -->

| Date | What I checked | Result | Change |
|---|---|---|---|
| 2026-11-11 | Ran `run.bat` on my laptop | All tests passed, verdict PASS, numbers same as the report | None |
| 2026-11-11 | Broke the off-road rule (`* 10`) in `DistanceEngine.java` and ran `mvn -q test` | S1 failed (expected 2.22 km, got 2.58 km). Changed it back: all green | None |
| | | | |
