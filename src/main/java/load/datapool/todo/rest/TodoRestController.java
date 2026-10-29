package load.datapool.todo.rest;

import load.datapool.db.H2Template;
import load.datapool.prometheus.Exporter;
import load.datapool.service.Locker;
import load.datapool.service.LockerService;
import load.datapool.service.SequenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("api/v1")
public final class TodoRestController {

    private final Logger logger = LoggerFactory.getLogger(TodoRestController.class);
    private final H2Template jdbcOperations;
    private final Exporter exp;
    private final LockerService lockerService;
    private final SequenceService sequenceService;
    @Value("${datapool.getNext.maxRetries:1000}")
    private int getNextMaxRetries;
    private String extErrText = ""; //Extended error cause
    private int maxTableNameLength = 16;
    private int maxIndexLength = 25;
    @Value("${db.maxKeyColumnLength:1500}")
    private int maxKeyColumnLength;
    @Value("${db.maxTextColumnLength:32000}")
    private int maxTextColumnLength;
    //УЗ с правами на просмотр
    @Value("${db.viewer.username:viewer}")
    private String viewerName;
    @Value("${db.viewer.password:viewer}")
    private String viewerPassword;
    private int maxLengthNotValidRows = 25000;//Max length wrong lines for response
    private int seqCycleCache = 5;
    private ReentrantLock createPoolLock = new ReentrantLock();
    @Autowired
    public TodoRestController(H2Template jdbcOperations, Exporter exp, LockerService lockerService, SequenceService sequenceService) {
        this.jdbcOperations = jdbcOperations;
        this.exp = exp;
        this.lockerService = lockerService;
        this.sequenceService = sequenceService;
    }

    @GetMapping(path = "/get-next-value")
    public ResponseEntity<String> getNextData(@RequestParam(value = "env", defaultValue = "load") String env,
                                              @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                              @RequestParam(value = "locked", defaultValue = "false") boolean locked) {
        Instant start = Instant.now();
        String fullPoolName = fullPoolName(env, pool);

        if (!lockerService.poolExist(env, pool)){
            exp.incRequestsAndLatency(env, pool, "get-next-value", "Pool not found", start);
            return tableNotFindResponse(fullPoolName);
        }

        if (lockerService.isMarkedAsEmpty(env, pool)) {
            exp.incRequestsAndLatency(env, pool, "get-next-value", "Empty pool", start);
            return tableIsEmptyResponse(fullPoolName);
        }

        Locker locker = (Locker) lockerService.getLocker(env, pool);
        long sq = -1L;
        for (int attempt = 0; attempt < getNextMaxRetries; attempt++) {
            long epoch = locker.cursorEpoch();
            try {
                sq = sequenceService.nextRid(env, pool);
                if (!locked || lockerService.tryLock(env, pool, (int) sq)) {
                    String row = selectRow(fullPoolName, sq, locked);
                    if (row != null) {
                        exp.incRequestsAndLatency(env, pool, "get-next-value", null, start);
                        return ResponseEntity.ok(row);
                    }
                }
            } catch (DataAccessException e) {
                exp.incRequestsAndLatency(env, pool, "get-next-value", "Error data access", start);
                return ResponseEntity.badRequest().body(e.getMessage() + "\\\n ERROR " + env + "." + pool);
            }
            // строка занята или её нет (дыра в rid): сдвигаем курсор вперёд, один поток за раз
            if (locker.getRestartLock().tryLock()) {
                try {
                    if (locker.cursorEpoch() == epoch && !fixSequenceState(env, pool, sq)) {
                        exp.incRequestsAndLatency(env, pool, "get-next-value", "Sequence does not reset", start);
                        return tableIsEmptyResponse(fullPoolName);
                    }
                } finally {
                    locker.getRestartLock().unlock();
                }
            } else {
                locker.getRestartLock().lock();   // ждём, пока другой поток сдвинет курсор
                locker.getRestartLock().unlock();
            }
            if (lockerService.isMarkedAsEmpty(env, pool)) {
                exp.incRequestsAndLatency(env, pool, "get-next-value", "Empty pool", start);
                return tableIsEmptyResponse(fullPoolName);
            }
        }
        exp.incRequestsAndLatency(env, pool, "get-next-value", "Retry limit exceeded", start);
        return ResponseEntity.badRequest().body("Error. Need sync cache with database (see /pools.html). Retry limit " + getNextMaxRetries + " exceeded for " + env + "." + pool);
    }

    private String selectRow(String fullPoolName, long rid, boolean locked) {
        List<String> rows = jdbcOperations.query("select rid, text from " + fullPoolName + " where rid = ? and (locked = false or ?) limit 1",
                (resultSet, i) -> "{\"rid\":" + resultSet.getLong("rid") +
                        ",\"values\":" + resultSet.getString("text") +
                        ",\"locked\":" + locked + "}", rid, locked);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Сдвигает курсор на следующую свободную строку (строго после currentValue), иначе в начало; false — свободных строк нет. */
    private boolean fixSequenceState(String env, String pool, Long currentValue) {
        if (currentValue < 0) return false; //Some undefined sequence error
        try {
            int next = lockerService.firstBiggerUnlockedId(env, pool, currentValue.intValue());
            if (next == 0) {
                next = lockerService.firstUnlockRid(env, pool);   // конец пула — возвращаемся к началу
                if (next == 0) {
                    lockerService.markAsEmpty(env, pool);
                    sequenceService.restartRid(env, pool, 1);
                    ((Locker) lockerService.getLocker(env, pool)).nextCursorEpoch();
                    return false;
                }
            }
            sequenceService.restartRid(env, pool, next);
            ((Locker) lockerService.getLocker(env, pool)).nextCursorEpoch();
            logger.info("Sequence reseted for {}.{} was value = {}, now {}", env, pool, currentValue, next);
            return true;
        } catch (DataAccessException e) {
            logger.error(e.getMessage());
            return false;
        }
    }

    @PostMapping(path = "/put-value")
    public ResponseEntity<Object> putData(@RequestParam(value = "env", defaultValue = "load") String env,
                                          @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                          @RequestParam(value = "search-key", defaultValue = "") String searchKey,
                                          @RequestBody String text) {
        Instant start = Instant.now();

        if (!lockerService.poolExist(env, pool)) {
            // When datapool is absent
            if (pool.length() > maxTableNameLength)
            {
                exp.incRequestsAndLatency(env, pool, "put-value", "Pool name longer then 16 simbols", start);
                return ResponseEntity.badRequest().body("Datapool name can't be longer then " +
                        maxTableNameLength + " simbols. Your value is " + pool);
            }
            // Raise condition point check
            try {
                if (createPoolLock.tryLock(15, TimeUnit.SECONDS)){
                    logger.debug("Got lock createPoolLock for {}", pool);
                    if (!lockerService.poolExist(env, pool)) { // May be pool was created till renta was locked
                        if (!createTable(env, pool)){
                            // If some errors happened
                            createPoolLock.unlock();
                            exp.incRequestsAndLatency(env, pool, "put-value", "Datapool creation failed", start);
                            return ResponseEntity.badRequest().body("Datapool creation failed, undefined exception.");
                        }else {
                            if (lockerService.poolExist(env, pool)){ // poolExist have to rescan rows and init pool in lockers
                                // If table in db created successfully
                                logger.debug("Table for {} created successfully.", pool);
                                createPoolLock.unlock();
                            }else{
                                createPoolLock.unlock();
                                exp.incRequestsAndLatency(env, pool, "put-value", "Init datapool in locker failed", start);
                                return ResponseEntity.badRequest().body("Init datapool in locker failed.");
                            }
                        }
                    }else{
                        // If somebody has created datapool before our lock
                        createPoolLock.unlock();
                    }
                }else {
                    // When tryLock timed out
                    exp.incRequestsAndLatency(env, pool, "put-value", "Can't lock pool for create", start);
                    return ResponseEntity.badRequest().body("Can't lock pool for create " + pool);
                }
            } catch (InterruptedException e) {
                // if (createPoolLock.isLocked()) {createPoolLock.unlock();}
                exp.incRequestsAndLatency(env, pool, "put-value", "Lock pool exception for create", start);
                return ResponseEntity.badRequest().body("Lock pool exception for create " + pool);
            }
        }
        long rid = -1;
        {
            try {
                rid = sequenceService.nextMax(env, pool);
                if (searchKey.equals("")) {//No
                    this.jdbcOperations.update("insert into " + env + "." + pool + "(rid, text, locked) values (?,?,?);", rid, text, false);
                } else {
                    this.jdbcOperations.update("insert into " + env + "." + pool + "(rid, text, searchkey, locked) values (?,?,?,?);", rid, text, searchKey, false);
                }
            } catch (Exception e) {
                logger.error(e.getMessage());
                exp.incRequestsAndLatency(env, pool, "put-value", "Undefined INSERT exception", start);
                return ResponseEntity.badRequest().body(e.getMessage());
            }
            lockerService.ensureSize(env, pool, (int) rid);
            lockerService.markAsNotEmpty(env, pool);
        }
        exp.incRequestsAndLatency(env, pool, "put-value", null, start);
        return ResponseEntity.ok(new String((long) 1 + " Inserted:" + "locked = false; searchKey = " + searchKey));
    }

    @PostMapping(path = "/unlock")
    public ResponseEntity<String> unlockData(@RequestParam(value = "env", defaultValue = "load") String env,
                                             @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                             @RequestParam(value = "rid", defaultValue = "-1") String sRid,
                                             @RequestParam(value = "search-key", defaultValue = "") String searchKey,
                                             @RequestParam(value = "unlock-all", defaultValue = "false") boolean unlockAll) {
        Instant start = Instant.now();
        if (!lockerService.poolExist(env, pool)) {
            exp.incRequestsAndLatency(env, pool, "unlock", "Table not found", start);
            return tableNotFindResponse(fullPoolName(env, pool));
        }

        Long rid;
        try {
            rid = Long.parseLong(sRid.trim());  //<-- String to long here
        } catch (NumberFormatException nfe) {
            exp.incRequestsAndLatency(env, pool, "unlock", "NumberFormatException", start);
            return ResponseEntity.badRequest().body(new String(nfe.getMessage() + "\\\n" + extErrText + "\\\n ERROR " + env + "." + pool));
        }
        try {
            if (unlockAll) {
                lockerService.unlockAll(env, pool);
            } else {
                if (!sRid.equals("-1")) {
                    lockerService.unlock(env, pool, rid.intValue());
                } else {
                    if (!searchKey.equals("")) {
                        lockerService.unlock(env, pool, searchKey);
                    } else {
                        exp.incRequestsAndLatency(env, pool, "unlock", "Wrong request parameters", start);
                        return ResponseEntity.badRequest().body(new String("You must define one of the parameters variant:\n"
                                + "\t&unlock-all=true \n"
                                + "\t&rid=<row number> \n"
                                + "\t&search-key=<key value>"));
                    }
                }
            }
        } catch (DataAccessException e) {
            exp.incRequestsAndLatency(env, pool, "unlock", "NumberFormatException", start);
            return ResponseEntity.badRequest().body(new String(e.getMessage() + "\\\n" + extErrText + "\\\n ERROR " + env + "." + pool));
        }
        exp.incRequestsAndLatency(env, pool, "unlock", null, start);
        return ResponseEntity.ok(new String("Unlocked successfully!"));
    }

    @GetMapping(path = "/search-by-key")
    public ResponseEntity<String> getBySearchKey(@RequestParam(value = "env", defaultValue = "load") String env,
                                                 @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                                 @RequestParam(value = "search-key", defaultValue = "") String searchKey,
                                                 @RequestParam(value = "locked", defaultValue = "false") boolean locked) {
        Instant start = Instant.now();
        if (!lockerService.poolExist(env, pool)) {
            exp.incRequestsAndLatency(env, pool, "search-by-key", "Table not found", start);
            return tableNotFindResponse(fullPoolName(env, pool));
        }

        Long sq = (long) -1;
        extErrText = "";
        if (searchKey.equals("")) {
            exp.incRequestsAndLatency(env, pool, "search-by-key", "Wrong request parameters", start);
            return ResponseEntity.badRequest().body(new String("Undefined request parameter \"search-key\"."));
        }
        try {
            final Long[] rid = {null};
            // todo - долгий запрос!!!
            ResponseEntity<String> res = ResponseEntity.ok(this.jdbcOperations.queryForObject(
                    "select rid, text,searchkey, locked from " + env + "." + pool + " where searchkey = ? and locked = false limit 1",
                    (resultSet, i) -> {
                        rid[0] = resultSet.getLong("rid");
//                        exp.incRequestsAndLatency(env, pool, "search-by-key", null, start);
                        return new String("{\"rid\":" + resultSet.getLong("rid") +
                                ",\"searchkey\":\"" + resultSet.getString("searchkey") + "\"" +
                                ",\"values\":" + resultSet.getString("text") +
                                ",\"locked\":" + locked + "}");
                    }
                    , searchKey));

            if (locked) {
                if (!lockerService.tryLock(env, pool, rid[0].intValue())) {
                    exp.incRequestsAndLatency(env, pool, "search-by-key", "Already locked", start);
                    return ResponseEntity.badRequest().body("Row " + rid[0] + " was locked by another request. Repeat the request.");
                }
            }
            exp.incRequestsAndLatency(env, pool, "search-by-key", null, start);
            return res;

        } catch (DataAccessException e) {
            exp.incRequestsAndLatency(env, pool, "search-by-key", "DataAccessException", start);
            return ResponseEntity.badRequest().body(new String(e.getMessage() + "\\\n" + extErrText + "\\\n ERROR " + env + "." + pool + " searchkey = " + searchKey));
        }
    }

    @PostMapping(path = "/upload-csv-as-json")
    public ResponseEntity<String> uploadCSV(@RequestParam(value = "env", defaultValue = "load") String env,
                                            @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                            @RequestParam(value = "delimiter", defaultValue = ",") String delim,
                                            @RequestParam(value = "override", defaultValue = "false") boolean override,
                                            @RequestParam(value = "with-headers", defaultValue = "true") boolean withHeaders,
                                            @RequestParam("file") MultipartFile file) {
        Instant start = Instant.now();
        final String fullPoolName = fullPoolName(env, pool);

        if (!withHeaders) {
            exp.incRequestsAndLatency(env, pool, "upload-csv-as-json", "With out headers csv", start);
            return ResponseEntity.badRequest().body(new String("With out headers csv files not supported!"));
        }
        if (file.isEmpty()) {
            exp.incRequestsAndLatency(env, pool, "upload-csv-as-json", "File is empty", start);
            return ResponseEntity.badRequest().body(new String("File is empty!"));
        }
        if (override) {
            if (lockerService.poolExist(env, pool)){
                exp.removeMetrics(env, pool);
                dropTable(env, pool);
            }
        }

        if (!createTable(env, pool)){
            if (pool.length() > maxTableNameLength)
            {
                exp.incRequestsAndLatency(env, pool, "upload-csv-as-json", "Pool name longer then 16 simbols", start);
                return ResponseEntity.badRequest().body("Datapool name can't be longer then " +
                        maxTableNameLength + " simbols. Your value is " + pool);
            }
        }

        // parse CSV file
        BufferedReader reader = null;
        String notValidRows = "";
        try {
            reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
            String header = reader.readLine();
            if (header == null) {
                exp.incRequestsAndLatency(env, pool, "upload-csv-as-json", "Not found rows into file", start);
                return ResponseEntity.badRequest().body(new String("Not found rows into file!"));
            }
            int cntColumns = header.split(delim).length;
            String headers[] = header.split(delim);
            String line = reader.readLine();
            String json = "";
            int successCnt = 0;
            while (line != null) {
                String values[] = line.split(delim);
                if (values.length != cntColumns && notValidRows.length() < maxLengthNotValidRows) {
                    notValidRows += line + "\n";
                } else {
                    json = "{";
                    for (int i = 0; i < cntColumns; i++) {
                        json += "\"" + headers[i] + "\":\"" + values[i] + "\",";
                    }
                    if (json.endsWith(",")) {
                        json = json.substring(0, json.length() - 1);
                    }
                    json += "}";
                    this.jdbcOperations.update("insert into " + env + "." + pool + "(rid, text, locked) values (nextval (?),?,?);", getSeqPrefix(env, pool) + "_max", json, false);
                    successCnt++;
                }
                // read next line
                line = reader.readLine();
            }
            reader.close();

            if (override) {
                lockerService.putPool(env, pool, successCnt);
            }else{
                lockerService.add(env, pool, successCnt);
            }

            if (!notValidRows.equals("")) {
                exp.incRequestsAndLatency(env, pool, "upload-csv-as-json", "Uploaded with wrong lines", start);
                return ResponseEntity.badRequest().body("Success uploaded rows: " + successCnt + ". Wrong parse some lines:\n" + notValidRows.substring(0, Math.min(notValidRows.length(), maxLengthNotValidRows)));
            }
            exp.incRequestsAndLatency(env, pool, "upload-csv-as-json", null, start);
            return ResponseEntity.ok().body("Ok! Uploaded rows: " + successCnt);
        } catch (Exception ex) {
            exp.incRequestsAndLatency(env, pool, "upload-csv-as-json", "File parse exception", start);
            return ResponseEntity.badRequest().body("File parse exception: " + ex.getMessage() + "\n" + notValidRows);
        }
    }

    private ResponseEntity<String> tableNotFindResponse(String poolName) {
        return ResponseEntity.badRequest().body("Table " + poolName + " not found in lockerService");
    }

    private ResponseEntity<String> tableIsEmptyResponse(String poolName) {
        return ResponseEntity.badRequest().body("Incorrect result size: expected 1, actual 0\\\n" +
                "May be table is empty. \\\n" +
                " ERROR " + poolName);
    }

    private String getSeqPrefix(String env, String pool) {
        return sequenceService.seqPrefix(env, pool);
    }

    private boolean createTable(String env, String pool) {
        logger.error("createTable");
        if (pool.length() > maxTableNameLength)
        {
            logger.error("Datapool name can't be longer then {} simbols. Your value is {}", maxTableNameLength, pool);
            return false;
        }

        if (lockerService.poolExist(env, pool)) {
            logger.info("Datapool {} already exists.", pool);
            return true;
        }
        try {
        	this.jdbcOperations.execute("create schema if not exists " + env + ";");
            this.jdbcOperations.execute("" +
                    "CREATE USER IF NOT EXISTS " + viewerName + " PASSWORD '" + viewerPassword + "';\n" +
                    "GRANT SELECT ON SCHEMA  " + env + " TO " + viewerName + ";");
            this.jdbcOperations.execute("" +
                    "create table " + env + "." + pool +
                    "(" +
                    "        rid           bigint primary key," +
                    "        text        varchar(" + maxTextColumnLength + ")," +
                    "        searchkey        varchar(" + maxKeyColumnLength + ")," +
                    "        locked         boolean   not null default false" +
                    ");");
            String indexPrefix = new String("idx_" + env + "" + pool);
            indexPrefix = indexPrefix.substring(0, indexPrefix.length() <= maxIndexLength ? indexPrefix.length() : maxIndexLength);
            this.jdbcOperations.execute("CREATE INDEX " + indexPrefix + "_rid ON  " + env + "." + pool + " (rid);");
            this.jdbcOperations.execute("CREATE INDEX " + indexPrefix + "_lc ON  " + env + "." + pool + " (locked);");
            this.jdbcOperations.execute("CREATE INDEX " + indexPrefix + "_sk ON  " + env + "." + pool + " (searchkey, locked);");


            this.jdbcOperations.execute("DROP SEQUENCE if exists " + getSeqPrefix(env, pool) + "_max;");
            this.jdbcOperations.execute("DROP SEQUENCE if exists " + getSeqPrefix(env, pool) + "_rid;");
            this.jdbcOperations.execute("CREATE SEQUENCE " + getSeqPrefix(env, pool) + "_max MINVALUE 0 NO CYCLE CACHE " + seqCycleCache + " INCREMENT 1 START 1;"); //for append insert
            this.jdbcOperations.execute("CREATE SEQUENCE " + getSeqPrefix(env, pool) + "_rid MINVALUE 0 NO CYCLE CACHE " + seqCycleCache + " INCREMENT 1 START 1;"); //for get value
//            try {
//                Thread.sleep(30000);
//            } catch (InterruptedException e) {
//                throw new RuntimeException(e);
//            }
            lockerService.putPool(env, pool);
            return true;
        } catch (DataAccessException e) {
            logger.error(e.getMessage());
            return false;
        }
    }

    private boolean dropTable(String env, String pool) {
        lockerService.deletePool(env, pool);
        try {
            this.jdbcOperations.execute("drop table if exists " + env + "." + pool + ";");
            String indexPrefix = new String("idx_" + env + "" + pool);
            indexPrefix = indexPrefix.substring(0, indexPrefix.length() <= maxIndexLength ? indexPrefix.length() : maxIndexLength);
            this.jdbcOperations.execute("drop INDEX if exists " + indexPrefix + "_rid");
            this.jdbcOperations.execute("drop INDEX if exists " + indexPrefix + "_lc");
            this.jdbcOperations.execute("DROP SEQUENCE if exists " + getSeqPrefix(env, pool) + "_max;");
            this.jdbcOperations.execute("DROP SEQUENCE if exists " + getSeqPrefix(env, pool) + "_rid;");
            return true;
        } catch (DataAccessException e) {
            logger.error(e.getMessage());
            return false;
        }
    }

    private String fullPoolName(String env, String pool) {
        return env + "." + pool;
    }

    @DeleteMapping(path = "/drop-pool")
    public ResponseEntity<String> deletePool(@RequestParam(value = "env", defaultValue = "load") String env,
                                             @RequestParam(value = "pool", defaultValue = "testpool") String pool) {
        Instant start = Instant.now();
        if (lockerService.poolExist(env, pool)) {
            try {
                dropTable(env, pool);
                exp.removeMetrics(env, pool);
                exp.incRequestsAndLatency(env, pool, "drop-pool", null, start);
                return ResponseEntity.ok().body("Pool deleted successfully!");
            } catch (Exception ex) {
                logger.error(ex.getMessage());
                exp.incRequestsAndLatency(env, pool, "drop-pool", "Failed to delete pool", start);
                return ResponseEntity.badRequest().body("Failed to delete pool");
            }
        } else {
            exp.incRequestsAndLatency(env, pool, "drop-pool", "Pool not found", start);
            return ResponseEntity.badRequest().body("Pool not found");
        }
    }
    
    // reset sequence to 1 (zero point of pool)
    @GetMapping(path = "/resetseq")
    public ResponseEntity<Object> resetseq(@RequestParam(value = "env", defaultValue = "load") String env,
                                          @RequestParam(value = "pool", defaultValue = "testpool") String pool) {
        Instant start = Instant.now();
        Long newSqValue = 1L;
        try {
        	sequenceService.restartRid(env, pool, newSqValue);
        } catch (Exception e) {
            logger.error(e.getMessage());
            exp.incRequestsAndLatency(env, pool, "resetseq", "Exception", start);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        exp.incRequestsAndLatency(env, pool, "resetseq", null, start);
        return ResponseEntity.ok(new String("Row id sequence has been reseted for pool "+pool));
    }
}
