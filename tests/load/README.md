# Load / regression tests (Gatling)

Gatling 3.14.3 on JDK 17 + Maven. Scenario `datapool.DatapoolSimulation`: 5 pools (p1-p3 read, p4 read with lock, p5 insert + read by different tracks).

Run against a started datapool:

    mvn gatling:test -Dgatling.simulationClass=datapool.DatapoolSimulation \
        -Dbase=http://localhost:8080 -Drps=1000 -Dstep=500 -Dsteps=8 -DlevelSec=60 -DsearchW=0

Parameters (-D): `rps` start, `step`, `steps`, `levelSec`, `rampSec`, `warmSec`, `preLockPct`, `longPct`, `holdMs`, `poisonRps` (failing inserts per second, makes a rid hole), `searchW` (share of search-by-key, 0 = off).

After the run `Diag` prints the verdict: duplicate locks (the same locked row issued twice), error kinds, and the pool walk of p5. It also calls `GET /api/v1/pools`.

Regression: duplicates must be 0, no HTTP 500 / StackOverflowError, `/api/v1/pools` has no diverged pools after the load. Goal: 3500-5000 rps without errors.

Note on `poisonRps` runs: every failed insert consumes one rid, so the p5 audit reports `missing = unexpected = <number of failed inserts>`. That is the shift of rids, not lost rows. The check is that the walk completes without errors.

Reference results (8 cores, service and Gatling on one machine, 1000 -> 4500 rps, 60 s per step):

| version | duplicate locks | errors | notes |
|---|---|---|---|
| 1.7.8 | 178 per 145k locks | 0 | hole in rid: endless loop, StackOverflowError |
| 1.8.0 | 0 per 155k locks | 0 of 1.45M requests | hole in rid: no loop, no ALTER SEQUENCE storm |
| 1.8.0 + search-by-key 5% | 0 per 114k locks | 0 of 1.1M requests (500 -> 4500 rps, 40 s steps) | search p99 20 ms, 55k searches, no collapse (1.7.8 collapsed at ~500 rps) |

If Gatling is upgraded, remove `target` first (stale classes give NoSuchMethodError).
