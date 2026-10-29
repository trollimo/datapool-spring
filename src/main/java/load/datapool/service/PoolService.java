package load.datapool.service;

import load.datapool.prometheus.Exporter;
import load.datapool.repository.PoolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Сценарии использования пулов: выдача, вставка, блокировки, поиск по ключу, загрузка, удаление, сброс курсора.
 * Ошибки приходят как PoolException (статус, текст ответа, подпись метрики). Метрики запросов пишет вызывающий слой (REST, MCP).
 */
@Service
public class PoolService {

    /** В тексте ошибок остался разделитель прежних версий: обратный слеш и перевод строки. */
    private static final String SEP = "\\\n";
    private static final int MAX_TABLE_NAME_LENGTH = 16;
    private static final int MAX_LENGTH_NOT_VALID_ROWS = 25000; // Max length wrong lines for response

    private final Logger logger = LoggerFactory.getLogger(PoolService.class);
    private final PoolRepository repository;
    private final LockerService lockerService;
    private final SequenceService sequenceService;
    private final Exporter exp;
    private final ReentrantLock createPoolLock = new ReentrantLock();

    @Value("${datapool.getNext.maxRetries:1000}")
    private int getNextMaxRetries;

    @Autowired
    public PoolService(PoolRepository repository, LockerService lockerService, SequenceService sequenceService, Exporter exp) {
        this.repository = repository;
        this.lockerService = lockerService;
        this.sequenceService = sequenceService;
        this.exp = exp;
    }

    // ---- выдача ----

    public String nextValue(String env, String pool, boolean locked) {
        PoolNames.require(env, pool);
        String fullPoolName = fullName(env, pool);

        if (!lockerService.poolExist(env, pool)) {
            throw new PoolException(notFoundText(fullPoolName), "Pool not found");
        }
        if (lockerService.isMarkedAsEmpty(env, pool)) {
            throw new PoolException(emptyText(fullPoolName), "Empty pool");
        }

        Locker locker = lockerService.getLocker(env, pool);
        long sq = -1L;
        for (int attempt = 0; attempt < getNextMaxRetries; attempt++) {
            long epoch = locker.cursorEpoch();
            try {
                sq = sequenceService.nextRid(env, pool);
                if (!locked || lockerService.tryLock(env, pool, (int) sq)) {
                    String row = repository.selectRowJson(env, pool, sq, locked);
                    if (row != null) {
                        return row;
                    }
                }
            } catch (DataAccessException e) {
                throw new PoolException(e.getMessage() + SEP + " ERROR " + env + "." + pool, "Error data access");
            }
            // строка занята или её нет (дыра в rid): сдвигаем курсор вперёд, один поток за раз
            if (locker.getRestartLock().tryLock()) {
                try {
                    if (locker.cursorEpoch() == epoch && !fixSequenceState(env, pool, sq)) {
                        throw new PoolException(emptyText(fullPoolName), "Sequence does not reset");
                    }
                } finally {
                    locker.getRestartLock().unlock();
                }
            } else {
                locker.getRestartLock().lock();   // ждём, пока другой поток сдвинет курсор
                locker.getRestartLock().unlock();
            }
            if (lockerService.isMarkedAsEmpty(env, pool)) {
                throw new PoolException(emptyText(fullPoolName), "Empty pool");
            }
        }
        throw new PoolException("Error. Need sync cache with database (see /pools.html). Retry limit " + getNextMaxRetries + " exceeded for " + env + "." + pool,
                "Retry limit exceeded");
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
                    lockerService.getLocker(env, pool).nextCursorEpoch();
                    return false;
                }
            }
            sequenceService.restartRid(env, pool, next);
            lockerService.getLocker(env, pool).nextCursorEpoch();
            logger.info("Sequence reseted for {}.{} was value = {}, now {}", env, pool, currentValue, next);
            return true;
        } catch (DataAccessException e) {
            logger.error(e.getMessage());
            return false;
        }
    }

    // ---- вставка ----

    /** Вставляет строку; пул создаётся, если его нет. Возвращает текст ответа. */
    public String put(String env, String pool, String searchKey, String text) {
        PoolNames.require(env, pool);
        if (!lockerService.poolExist(env, pool)) {
            // When datapool is absent
            if (pool.length() > MAX_TABLE_NAME_LENGTH) {
                throw new PoolException("Datapool name can't be longer then " + MAX_TABLE_NAME_LENGTH + " simbols. Your value is " + pool,
                        "Pool name longer then 16 simbols");
            }
            createPoolOnce(env, pool);
        }
        long rid;
        try {
            rid = sequenceService.nextMax(env, pool);
            repository.insert(env, pool, rid, text, searchKey);
        } catch (Exception e) {
            logger.error(e.getMessage());
            throw new PoolException(e.getMessage(), "Undefined INSERT exception");
        }
        lockerService.ensureSize(env, pool, (int) rid);
        lockerService.markAsNotEmpty(env, pool);
        return (long) 1 + " Inserted:" + "locked = false; searchKey = " + searchKey;
    }

    /** Raise condition point check: один поток создаёт пул, остальные ждут и видят уже созданный. */
    private void createPoolOnce(String env, String pool) {
        try {
            if (!createPoolLock.tryLock(15, TimeUnit.SECONDS)) {
                throw new PoolException("Can't lock pool for create " + pool, "Can't lock pool for create");
            }
        } catch (InterruptedException e) {
            throw new PoolException("Lock pool exception for create " + pool, "Lock pool exception for create");
        }
        try {
            logger.debug("Got lock createPoolLock for {}", pool);
            if (!lockerService.poolExist(env, pool)) { // May be pool was created till lock was taken
                if (!createTable(env, pool)) {
                    throw new PoolException("Datapool creation failed, undefined exception.", "Datapool creation failed");
                }
                if (!lockerService.poolExist(env, pool)) { // poolExist have to rescan rows and init pool in lockers
                    throw new PoolException("Init datapool in locker failed.", "Init datapool in locker failed");
                }
                logger.debug("Table for {} created successfully.", pool);
            }
        } finally {
            createPoolLock.unlock();
        }
    }

    private boolean createTable(String env, String pool) {
        logger.debug("createTable");
        if (pool.length() > MAX_TABLE_NAME_LENGTH) {
            logger.error("Datapool name can't be longer then {} simbols. Your value is {}", MAX_TABLE_NAME_LENGTH, pool);
            return false;
        }
        if (lockerService.poolExist(env, pool)) {
            logger.info("Datapool {} already exists.", pool);
            return true;
        }
        try {
            repository.createPool(env, pool);
            lockerService.putPool(env, pool);
            return true;
        } catch (DataAccessException e) {
            logger.error(e.getMessage());
            return false;
        }
    }

    // ---- блокировки ----

    public String unlock(String env, String pool, String sRid, String searchKey, boolean unlockAll) {
        PoolNames.require(env, pool);
        if (!lockerService.poolExist(env, pool)) {
            throw new PoolException(notFoundText(fullName(env, pool)), "Table not found");
        }
        Long rid;
        try {
            rid = Long.parseLong(sRid.trim());
        } catch (NumberFormatException nfe) {
            throw new PoolException(nfe.getMessage() + SEP + SEP + " ERROR " + env + "." + pool, "NumberFormatException");
        }
        try {
            if (unlockAll) {
                lockerService.unlockAll(env, pool);
            } else if (!sRid.equals("-1")) {
                lockerService.unlock(env, pool, rid.intValue());
            } else if (!searchKey.equals("")) {
                lockerService.unlock(env, pool, searchKey);
            } else {
                throw new PoolException("You must define one of the parameters variant:\n"
                        + "\t&unlock-all=true \n"
                        + "\t&rid=<row number> \n"
                        + "\t&search-key=<key value>", "Wrong request parameters");
            }
        } catch (DataAccessException e) {
            throw new PoolException(e.getMessage() + SEP + SEP + " ERROR " + env + "." + pool, "NumberFormatException");
        }
        return "Unlocked successfully!";
    }

    // ---- поиск по ключу ----

    public String searchByKey(String env, String pool, String searchKey, boolean locked) {
        PoolNames.require(env, pool);
        if (!lockerService.poolExist(env, pool)) {
            throw new PoolException(notFoundText(fullName(env, pool)), "Table not found");
        }
        if (searchKey.equals("")) {
            throw new PoolException("Undefined request parameter \"search-key\".", "Wrong request parameters");
        }
        try {
            final Long[] rid = {null};
            String res = repository.findFreeByKey(env, pool, searchKey, rs -> {
                rid[0] = rs.getLong("rid");
                return "{\"rid\":" + rs.getLong("rid") +
                        ",\"searchkey\":\"" + rs.getString("searchkey") + "\"" +
                        ",\"values\":" + rs.getString("text") +
                        ",\"locked\":" + locked + "}";
            });
            if (locked && !lockerService.tryLock(env, pool, rid[0].intValue())) {
                throw new PoolException("Row " + rid[0] + " was locked by another request. Repeat the request.", "Already locked");
            }
            return res;
        } catch (DataAccessException e) {
            throw new PoolException(e.getMessage() + SEP + SEP + " ERROR " + env + "." + pool + " searchkey = " + searchKey, "DataAccessException");
        }
    }

    // ---- загрузка CSV ----

    /** Загрузка CSV: первая строка — заголовки, каждая следующая становится JSON-объектом из строк. */
    public String uploadCsv(String env, String pool, String delim, boolean override, boolean withHeaders, long fileSize, InputStream in) {
        PoolNames.require(env, pool);
        if (!withHeaders) {
            throw new PoolException("With out headers csv files not supported!", "With out headers csv");
        }
        if (fileSize <= 0) {
            throw new PoolException("File is empty!", "File is empty");
        }
        if (override && lockerService.poolExist(env, pool)) {
            exp.removeMetrics(env, pool);
            dropPoolData(env, pool);
        }
        if (!createTable(env, pool)) {
            if (pool.length() > MAX_TABLE_NAME_LENGTH) {
                throw new PoolException("Datapool name can't be longer then " + MAX_TABLE_NAME_LENGTH + " simbols. Your value is " + pool,
                        "Pool name longer then 16 simbols");
            }
        }

        String notValidRows = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                throw new PoolException("Not found rows into file!", "Not found rows into file");
            }
            int cntColumns = header.split(delim).length;
            String[] headers = header.split(delim);
            String line = reader.readLine();
            int successCnt = 0;
            while (line != null) {
                String[] values = line.split(delim);
                if (values.length != cntColumns && notValidRows.length() < MAX_LENGTH_NOT_VALID_ROWS) {
                    notValidRows += line + "\n";
                } else {
                    StringBuilder json = new StringBuilder("{");
                    for (int i = 0; i < cntColumns; i++) {
                        json.append('"').append(headers[i]).append("\":\"").append(values[i]).append("\",");
                    }
                    if (json.charAt(json.length() - 1) == ',') {
                        json.setLength(json.length() - 1);
                    }
                    json.append('}');
                    repository.insertCsvRow(env, pool, json.toString());
                    successCnt++;
                }
                line = reader.readLine();
            }

            if (override) {
                lockerService.putPool(env, pool, successCnt);
            } else {
                lockerService.add(env, pool, successCnt);
            }

            if (!notValidRows.equals("")) {
                throw new PoolException("Success uploaded rows: " + successCnt + ". Wrong parse some lines:\n"
                        + notValidRows.substring(0, Math.min(notValidRows.length(), MAX_LENGTH_NOT_VALID_ROWS)), "Uploaded with wrong lines");
            }
            return "Ok! Uploaded rows: " + successCnt;
        } catch (PoolException e) {
            throw e;
        } catch (IOException | RuntimeException ex) {
            throw new PoolException("File parse exception: " + ex.getMessage() + "\n" + notValidRows, "File parse exception");
        }
    }

    // ---- удаление и сброс ----

    public String drop(String env, String pool) {
        PoolNames.require(env, pool);
        if (!lockerService.poolExist(env, pool)) {
            throw new PoolException("Pool not found", "Pool not found");
        }
        try {
            dropPoolData(env, pool);
            exp.removeMetrics(env, pool);
            return "Pool deleted successfully!";
        } catch (Exception ex) {
            logger.error(ex.getMessage());
            throw new PoolException("Failed to delete pool", "Failed to delete pool");
        }
    }

    private boolean dropPoolData(String env, String pool) {
        lockerService.deletePool(env, pool);
        try {
            repository.dropPool(env, pool);
            return true;
        } catch (DataAccessException e) {
            logger.error(e.getMessage());
            return false;
        }
    }

    /** Курсор выдачи в начало пула (sequence = 1). */
    public String resetSequence(String env, String pool) {
        PoolNames.require(env, pool);
        try {
            sequenceService.restartRid(env, pool, 1);
        } catch (Exception e) {
            logger.error(e.getMessage());
            throw new PoolException(e.getMessage(), "Exception");
        }
        return "Row id sequence has been reseted for pool " + pool;
    }

    // ---- тексты ----

    private String fullName(String env, String pool) { return env + "." + pool; }

    private String notFoundText(String poolName) { return "Table " + poolName + " not found in lockerService"; }

    private String emptyText(String poolName) {
        return "Incorrect result size: expected 1, actual 0\\\n" +
                "May be table is empty. \\\n" +
                " ERROR " + poolName;
    }
}
