package load.datapool.service;

import load.datapool.db.H2Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class BaseLockerService implements LockerService {

    private final Logger logger = LoggerFactory.getLogger(BaseLockerService.class);

    private static final List<String> systemSchemas = Arrays.asList("INFORMATION_SCHEMA", "PUBLIC");
    private final Map<String, Locker> lockers = new ConcurrentHashMap<>();

    private final H2Template jdbcOperations;
    private final TableService tableService;
    private final int batchRows;
    private final boolean autoLoadPoolFromDB; // For auto load datapool from DB. For debug issues

    @Autowired
    public BaseLockerService(H2Template jdbcOperations, TableService tableService,
                             @Value("${lockerService.batchRows}") int batchRows,
                             @Value("${lockerService.autoLoadPoolFromDB:false}") boolean autoLoadPoolFromDB
                             ) {
        this.jdbcOperations = jdbcOperations;
        this.tableService = tableService;
        this.batchRows = batchRows;
        this.autoLoadPoolFromDB = autoLoadPoolFromDB;
    }

    @PostConstruct
    @Override
    public void initLocks() {
        logger.info("Init start");
        try {
            List<String> schemas = showSchemas();
            schemas.forEach(schema -> {
                selectTables(schema).forEach(tableName -> {
                    selectLocksFromTable(schema, tableName);
                });
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
        logger.info("Init finish");
    }

    private List<String> showSchemas() {
        final List<String> schemas = new ArrayList<>();
        final String selectSchemas = "SHOW SCHEMAS";
        jdbcOperations.queryForList(selectSchemas)
                .forEach(map -> map.forEach((key, value) -> {
                    final String schema = value.toString();
                    if (systemSchemas.contains(schema))
                        return;
                    schemas.add(schema);
                }));
        return schemas;
    }

    private List<String> selectTables(String schema) {
        final String selectTables = "SELECT TABLE_NAME FROM information_schema.tables where table_schema = ?";
        final String[] args = new String[]{schema};
        return jdbcOperations.queryForList(selectTables, args, String.class);
    }

    private void selectLocksFromTable(String schema, String tableName) {
        final String fullTableName = tableService.fullName(schema, tableName);
        logger.info("Locker scan table: " + fullTableName);

        try {
            if (!containsColumns(schema, tableName))
                return;
            Locker locker;
            Integer maxRid = maxRid(fullTableName);
            if (maxRid == null)
                locker = new BaseLocker(fullTableName);
            else
                locker = new BaseLocker(fullTableName, maxRid);

            lockers.put(fullTableName, locker);

            final Integer lockedRows = lockedRows(fullTableName);
            if (lockedRows.equals(0))
                return;

            final String selectRids = "SELECT rid FROM " + fullTableName + " WHERE rid > ? AND locked = true limit ?";
            final Object[] args = new Object[]{0, batchRows};
            final int ridIndex = 0;

            for (int rows = lockedRows; rows > 0; rows -= batchRows) {
                List<Integer> rids = jdbcOperations.queryForList(selectRids, args, Integer.class);
                rids.forEach(locker::lock);
                args[ridIndex] = rids.get(rids.size() - 1);
            }
            logger.info("\tLockedRows: " + lockedRows);
        } catch (Exception e) {
            logger.error("Error scan table:" + fullTableName, e);
        }
    }

    private boolean containsColumns(String schema, String tableName) {
        return containsRidColumn(schema, tableName) &&
                containsLockedColumn(schema, tableName);
    }

    private boolean containsLockedColumn(String schema, String tableName) {
        final String lockedColumn = "LOCKED";
        return containsColumn(schema, tableName, lockedColumn);
    }

    private boolean containsRidColumn(String schema, String tableName) {
        final String lockedColumn = "RID";
        return containsColumn(schema, tableName, lockedColumn);
    }

    private boolean containsColumn(String schema, String tableName, String column) {
        final String selectColumns = "SELECT count(column_name) FROM INFORMATION_SCHEMA.COLUMNS WHERE table_schema = ? AND table_name = ? AND column_name = ?";
        final String[] args = new String[]{schema, tableName, column};
        Integer lockedColNum = jdbcOperations.queryForObject(selectColumns, args, Integer.class);
        boolean contain = lockedColNum != null && lockedColNum > 0;
        if (!contain)
            logger.error("Table " + tableService.fullName(schema, tableName) + " not contain " + column + " column");
        return contain;
    }

    private Integer lockedRows(String fullTableName) {
        final String selectCountLocked = "SELECT count(rid) FROM " + fullTableName + " WHERE locked = true";
        return jdbcOperations.queryForObject(selectCountLocked, Integer.class);
    }

    private Integer maxRid(String fullTableName) {
        final String selectMaxRid = "SELECT max(rid) FROM " + fullTableName;
        Integer maxRid = jdbcOperations.queryForObject(selectMaxRid, Integer.class);
        return maxRid;
    }

    @Override
    public boolean poolExist(String env, String pool) {
        if (lockers.get(tableService.fullName(env, pool)) != null){
            return true;
        }
// При пересоздании пула под нагрузкой, возникает коллизия (таблица и сиквенсы еще создаются, а потоки уже пытаются загрузить в приложение этот пул)
        synchronized (this) {
            final int trueNum = 1;
            AtomicInteger exist = new AtomicInteger(0);
            try {
                showSchemas().forEach(schema -> {
                    if (!schema.equalsIgnoreCase(env))
                        return;
                    selectTables(schema).forEach(tableName -> {
                        if (tableName.equalsIgnoreCase(pool))
                            exist.set(trueNum);
                    });
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (exist.get() == 1) { //IF there is pool in db, but there is not in lockers
                if (autoLoadPoolFromDB){
                    selectLocksFromTable(env, pool); //Load locks
                    logger.warn("{} was auto loaded from DB. Check DB structure and app lockers cache.", tableService.fullName(env, pool));
                }
                logger.warn("{} is presented in database, but is absent in application cache.", tableService.fullName(env, pool));
            }
            //return exist.get() == trueNum;
        }
        return false;
    }

    @Override
    public boolean isMarkedAsEmpty(String env, String pool) {
        pool = tableService.fullName(env, pool);
        return lockers.get(pool).isMarkedAsEmpty();
    }

    @Override
    public void markAsEmpty(String env, String pool) {
        pool = tableService.fullName(env, pool);
        lockers.get(pool).markAsEmpty();
    }

    @Override
    public void markAsNotEmpty(String env, String pool) {
        pool = tableService.fullName(env, pool);
        lockers.get(pool).markAsNotEmpty();
    }

    @Override
    public Object getLocker(String env, String pool) {
        pool = tableService.fullName(env, pool);
        return lockers.get(pool);
    }

    @Override
    public void putPool(String env, String pool) {
        pool = tableService.fullName(env, pool);
        lockers.put(pool, new BaseLocker(pool));
    }

    @Override
    public void putPool(String env, String pool, int size) {
        pool = tableService.fullName(env, pool);
        lockers.put(pool, new BaseLocker(pool, size));
    }

    @Override
    public void deletePool(String env, String pool) {
        pool = tableService.fullName(env, pool);
        lockers.remove(pool);
    }

    @Override
    public void add(String env, String pool) {
        add(env, pool, 1);
    }

    @Override
    public void add(String env, String pool, int count) {
        pool = tableService.fullName(env, pool);
        synchronized (lockers.get(pool)) {
            lockers.get(pool).add(count);
            lockers.get(pool).markAsNotEmpty();
        }
    }

    @Override
    public void lock(String env, String pool, int rid) {

        pool = tableService.fullName(env, pool);
        synchronized (lockers.get(pool)) {
            jdbcOperations.update("update " + pool + " set locked = true where  rid = ? and locked = false;", rid);
            lockers.get(pool).lock(rid);
        }
    }

    /** Атомарно занимает строку: true только если UPDATE действительно перевёл locked false -> true. */
    @Override
    public boolean tryLock(String env, String pool, int rid) {
        pool = tableService.fullName(env, pool);
        int n = jdbcOperations.update("update " + pool + " set locked = true where rid = ? and locked = false", rid);
        if (n == 1) {
            Locker l = lockers.get(pool);
            synchronized (l) { l.lock(rid); }
        }
        return n == 1;
    }

    @Override
    public void unlock(String env, String pool, int rid) {
        pool = tableService.fullName(env, pool);
        synchronized (lockers.get(pool)) {
            jdbcOperations.update("update " + pool + " set locked = false where rid = ?", rid);
            lockers.get(pool).unlock(rid);
            lockers.get(pool).markAsNotEmpty();
        }
    }

    @Override
    public void unlock(String env, String pool, String searchKey) {
        pool = tableService.fullName(env, pool);
        synchronized (lockers.get(pool)) {
            jdbcOperations.update("update " + pool + " set locked = false where searchkey = ?", searchKey);
            Integer rid = jdbcOperations.queryForObject("SELECT rid FROM " + pool + " WHERE searchkey = ? limit 1", Integer.class, searchKey);
            if (rid != null) lockers.get(pool).unlock(rid);
            lockers.get(pool).markAsNotEmpty();
        }
    }

    @Override
    public void unlockAll(String env, String pool) {
        pool = tableService.fullName(env, pool);
        synchronized (lockers.get(pool)) {
            jdbcOperations.update("update " + pool + " set locked = false where locked is null; update " + pool + " set locked = false where locked =true;"); //Fast variant
            lockers.get(pool).unlockAll();
            lockers.get(pool).markAsNotEmpty();
        }
    }

    @Override
    public int firstUnlockRid(String env, String pool) {
        pool = tableService.fullName(env, pool);
        return lockers.get(pool).firstUnlockId();
    }

    @Override
    public int firstBiggerUnlockedId(String env, String pool, int id) {
        pool = tableService.fullName(env, pool);
        return lockers.get(pool).firstBiggerUnlockedId(id);
    }

    @Override
    public void ensureSize(String env, String pool, int size) {
        lockers.get(tableService.fullName(env, pool)).ensureSize(size);
    }

    @Override
    public Map<String, Locker> allLockers() {
        return new TreeMap<>(lockers);
    }

    @Override
    public void reloadFromDb(String env, String pool) {
        String full = tableService.fullName(env, pool);
        Locker locker = lockers.get(full);
        synchronized (locker) {
            Integer maxRid = maxRid(full);
            List<Integer> locked = jdbcOperations.queryForList("SELECT rid FROM " + full + " WHERE locked = true", Integer.class);
            locker.reset(maxRid == null ? 0 : maxRid, locked);
        }
    }
}
