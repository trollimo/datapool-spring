# Changelog
Service for creating and utilizing test data pools via REST API.

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
- Unit and integration tests, Gatling regression tests in `tests/load`.

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