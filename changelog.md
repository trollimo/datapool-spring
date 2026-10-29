# Changelog
Service for creating and utilizing test data pools via REST API.

## [3.1.0]

### Added

- Page `/statistics.html`: five charts from the last hour (rps with a dashed total line that can be switched off, mean response time, errors per second, remaining share of the pool with a switch to the occupied share, frequency of cycling) and a summary table at the bottom (pool, average / last / maximum rps, response time, harmful loops; red when more than 0). Pure SVG and JavaScript, no libraries; multi-select filter by pool; the browser asks the server at most once per 15 seconds, also after a page reload.
- `GET /api/v1/statistics`: the history behind the page. A sampler takes a snapshot of the Prometheus counters and the lock cache every 15 seconds (`datapool.statistics.interval-seconds`) and keeps 60 minutes in memory (`datapool.statistics.window-minutes`); the history is empty after a restart. Nothing is added to the hot path of `get-next-value`.
- Metric `datapool_get_next_retries`: retries inside `get-next-value` (the row was locked or absent and the cursor was moved). A harmful loop is a request that used up `datapool.getNext.maxRetries` without finding data (`Retry limit exceeded`); an exhausted pool is not one.
- Minimal menu (Documentation, Pools, Statistics) at the top of every HTML page.
- `BaseLocker` keeps a counter of locked rows (O(1) instead of a pass through the array).
- Tests: 118 in total, among them 30% locked rows in several pools under concurrent reads (no endless loops), the harmful loop caused by a cache that disagrees with the DB (finite, counted, ends after repair).
- Gatling: `-DpreLockPools=p1,p2,p3,p4 -DpreLockPct=30` locks a share of rows of several pools for the whole test; the verdict fails when harmful loops are found; statistics are part of the human methods.

## [3.0.0]

Layered structure of the code. REST API, MCP, the DB structure (tables, indexes, sequences) and the data are unchanged: an existing database starts with the new jar without a migration (checked on a copy of the database from 2.0.0: schema identical, pools, locks and cursors restored).

### Changed

- `TodoRestController` (524 lines with SQL, DDL and business logic) is split into layers: thin controllers (`DatapoolController`, `CsvDownloadController`, `PoolsController`, `McpController`) -> `PoolService` (use cases), `CsvExportService`, `PoolDiagnosticsService`, `SequenceService` -> `PoolRepository` (all pool SQL and DDL in one place). The hot path (`get-next-value`) is moved without changes in the algorithm.
- Errors of the use cases are `PoolException` (status, text, metric label); the response texts of the previous versions are kept.
- MCP calls the services directly, not the REST controllers.
- `LockerService.getLocker` returns `Locker` (no casts).

### Fixed

- Names `env` and `pool` of every REST method are validated (letters, digits, underscore; up to 64 characters). Before, they were inserted into SQL as they came (SQL injection through `drop-pool`, `download/csv`, `resetseq` and others). Requests with a wrong name get HTTP 400 and do not create metric series.
- Removed the mutable field `extErrText` of the singleton controller (data race between requests).

### Added

- Tests: 101 (DB structure against the golden DDL, names, `PoolService` with mocks, bad names on every REST method).

## [2.0.0]

Major version: new interfaces for people and AI agents, `/api/v1/pools` family, removal of the cycle-fix endpoints (they had no meaning after 1.8.0). Existing pools and DB files need no migration.

### Added

- Page `/pools.html`: icon buttons in every row (reset counter, download, delete with a Yes/No confirmation), one bulk operation (reset counters of the selected pools). Download and delete use the existing REST methods `GET /api/v1/download/csv` and `DELETE /api/v1/drop-pool`; the downloaded CSV (headers = JSON fields, delimiter `;` or `,`) can be loaded back with `upload-csv-as-json` using the same delimiter.
- `api-specification/openapi.yaml`: OpenAPI 3.0.3 specification of all methods including `/mcp`; a test checks that it matches the real endpoints.
- `POST /mcp`: MCP server (Streamable HTTP, JSON-RPC 2.0) for AI agents. Tools: about, list_pools, counters_stats, repair_pools, reset_sequence, unlock, put_value, upload_csv, download_pool_csv, drop_pool; resource `datapool://about`. They call the same code as REST. `get-next-value` and `search-by-key` are not exposed. The `about` text explains how the pool works, how to troubleshoot, and that tool calls can disturb running tests. Protection: names validated (letters, digits, underscore), 30 tool calls per minute (`datapool.mcp.max-calls-per-minute`), one heavy operation (upload, download, drop, repair) at a time, download limited to `datapool.mcp.download.max-rows` (5000), upload to `datapool.mcp.upload.max-chars`, drop needs `confirm=ENV.POOL`.
- `SequenceService`: the only place that builds the sequence name and moves the read cursor (used by `/resetseq`, get-next-value and the repair).
- 79 unit and integration tests (REST, MCP, services, specification); Gatling scenarios for all human and MCP methods (2 calls per minute each, on service pools h1..h7) next to the load methods.

### Changed

- Version 2.0.0.

## [1.8.0]

### Fixed

- Counter divergence between the application cache and the DB, and endless looping of get-next-value (StackOverflowError) after a hole in rid: the search for the next free row now starts strictly after the current one; the recursion is replaced by a loop limited by `datapool.getNext.maxRetries` (default 1000).
- A late thread no longer rolls the sequence back (cursor epoch); only one thread per pool moves the cursor, the restart lock is per pool and always released.
- Duplicate rows with `locked=true`: a row is taken by an atomic `UPDATE ... SET locked=true WHERE locked=false`.
- The cache size follows max(rid) (not the number of inserts) after failed inserts and holes.
- Locker registry is a ConcurrentHashMap; put-value no longer holds a monitor during JDBC calls.

### Added

- `GET /api/v1/pools` and the page `/pools.html`: all pools, diverged first, sorting by any column.
- `POST /api/v1/pools/repair` and `/api/v1/pools/repair-batch`, buttons and checkboxes on the page: rebuild the cache from the DB and reset the cursor, without a DB migration.
- Unit and integration tests, Gatling regression tests in `tests/load` (Gatling 3.14.3, gatling-maven-plugin 4.21.12). The test prints `VERDICT: PASS/FAIL`.

### Removed

- `/set-cycle-fix-sequence` and `/get-cycle-fix-sequence` (they belonged to the recursion).

## [1.7.8]

### Added

- Optimization of method for download datapool content/api/v2/download/csv
- New URL connection options

## [1.7.7-alfa]

### Added

- Create api method for download datapool content/api/v2/download/csv for debug version

## [1.7.6]

### Added

- Create viewer database user for web console http://<host:port>/h2-console/login.do
- Make parameter db.maxTextColumnLength into application.properties

## [1.7.5]

### Changed

- Checking unlocked rows when frequent puts & gets

## [1.7.4]

### Changed

- Mute deadlock when happens DB and application cache collision

## [1.7.3]

### Changed

- Fix incorrect load big locked datapool

## [1.7.2]

### Changed

- Added details for prometheus logging
- Disable auto load datapool in BaseLockerService.poolExist
- Add mutex in /put-value when creating new datapool

## [1.7.1]

### Changed

- Fixed override=false in /upload-csv-as-json
- Mark empty pool in locker
- Fixed next biggest id
- Check poolExist only in app cache

## [1.6.5]

### Changed

- Fixed next sequence value

## [1.6.4]

### Added

- Resetting datapool sequence has done. /resetseq

### Changed

- Threads properties was optimized

## [1.6.3]

### Changed

- Add maxIdle logging 


## [1.6.2]

### Changed

- Fixed  reading from application.properties 


## [1.6.1]

### Changed

- Add table name maximum length
- Done drop datapool