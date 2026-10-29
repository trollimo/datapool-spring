package load.datapool.mcp;

/** Текст «about» для ИИ-агентов: отдаётся в initialize.instructions, ресурсом datapool://about и тулой about. */
public final class McpGuide {
    private McpGuide() {}

    public static final String ABOUT =
            "# Datapool MCP: about\n" +
            "\n" +
            "## What datapool is\n" +
            "A service that hands out test data to load tests. A pool is an H2 table ENV.POOL with columns rid (row number), text (JSON), searchkey, locked.\n" +
            "Load generators call get-next-value (reads rows in a ring, rid by rid; with locked=true the row is taken and nobody else gets it until unlock) and search-by-key.\n" +
            "The service keeps a cache of locks in memory (the 'locker'): its size must equal max(rid) in the DB and its locked set must equal the locked rows in the DB.\n" +
            "The read cursor is the database sequence SEQ_<POOL>_RID.\n" +
            "\n" +
            "## WARNING: do not harm running tests\n" +
            "- The service is probably under load right now (thousands of requests per second). Every call you make competes with it.\n" +
            "- Do NOT call tools in loops or in bulk. Default server limit is 30 tool calls per minute; heavy operations (upload, download, drop, repair) run one at a time and are refused with 'busy' when another one is running. When refused, wait; do not retry immediately.\n" +
            "- Start with read-only tools (list_pools, counters_stats). Change things only when diagnostics shows a problem or the user asked for it.\n" +
            "- drop_pool, unlock with unlock_all, upload_csv with override=true, reset_sequence and repair_pools change data that running tests use. Do them only on explicit user request and on the pool the user named. Never on all pools.\n" +
            "- get-next-value and search-by-key are deliberately NOT available through MCP: they take and lock test data and are the hot path of the load tests.\n" +
            "- download_pool_csv refuses pools larger than the configured limit (default 5000 rows); use the REST method /api/v1/download/csv for big pools.\n" +
            "\n" +
            "## Tools\n" +
            "- about: this text.\n" +
            "- list_pools {only_broken}: table of all pools, diverged first. Fields: lockerSize, dbMaxRid, dbRows, holes (= dbMaxRid - dbRows), dbLocked / lockerLocked, lockedOnlyInDb / lockedOnlyInLocker, empty, cursor, cursorEpoch, diverged.\n" +
            "- counters_stats: summary: pool count, how many are diverged, and the reason for each diverged pool.\n" +
            "- repair_pools {pools: [\"ENV.POOL\", ...]}: rebuild the locker from the DB (size = max(rid), locks from the table) and put the cursor on the first free row. No DB schema changes. Safe, but pools in use may issue a row twice during the instant of the rebuild.\n" +
            "- reset_sequence {env, pool}: set the cursor to 1 (start reading from the beginning). Does not touch the locker.\n" +
            "- unlock {env, pool, rid | search_key | unlock_all}: release locked rows.\n" +
            "- put_value {env, pool, value, search_key}: insert one row (value is a JSON object as a string). Creates the pool if it does not exist.\n" +
            "- upload_csv {env, pool, csv, delimiter, override}: load a CSV (first line = headers, every other line becomes a JSON object of strings). override=true drops and recreates the pool.\n" +
            "- download_pool_csv {env, pool, delimiter, all_columns}: pool as CSV. With all_columns=false the result can be loaded back with upload_csv (same delimiter). Values must not contain the delimiter or line breaks.\n" +
            "- drop_pool {env, pool, confirm}: delete the pool completely (table, sequences, metrics, locker). confirm must equal ENV.POOL.\n" +
            "\n" +
            "## Troubleshooting\n" +
            "1. Clients get 'Incorrect result size' / 'table is empty' for a pool that has data: the pool is marked empty or the cursor is off. Run list_pools: look at empty, lockerSize vs dbMaxRid, dbLocked vs lockerLocked. If diverged=true, run repair_pools for that pool.\n" +
            "2. Clients get HTTP 400 'Retry limit ... exceeded': the cache disagrees with the DB. repair_pools for that pool.\n" +
            "3. lockedOnlyInDb > 0: rows are locked in the DB but the cache thinks they are free (they will not be issued). lockedOnlyInLocker > 0: the cache holds rows that are free in the DB. Both are fixed by repair_pools.\n" +
            "4. holes > 0: rid numbers without rows (failed inserts, deleted rows). This is not an error, the reader skips them. Large holes only waste read attempts.\n" +
            "5. All rows locked: the pool really is exhausted. Use unlock (one rid or search_key); unlock_all only when the user asks.\n" +
            "6. Pool is missing in list_pools but exists in the DB: the application cache lost it. Restart the service or upload the pool again.\n" +
            "7. Tool answers 'rate limited' or 'busy': do nothing for a minute, then try once.\n" +
            "\n" +
            "Names: env and pool contain only letters, digits and underscore. Pool name is at most 16 characters. Names are case-insensitive (stored upper-case).\n";
}
