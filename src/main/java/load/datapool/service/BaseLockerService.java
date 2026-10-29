package load.datapool.service;

import load.datapool.repository.PoolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Реестр пулов и кэш блокировок (локеры). Изменения блокировок идут и в БД, и в кэш. */
@Service
public class BaseLockerService implements LockerService {

    private final Logger logger = LoggerFactory.getLogger(BaseLockerService.class);

    private static final List<String> systemSchemas = Arrays.asList("INFORMATION_SCHEMA", "PUBLIC");
    private final Map<String, Locker> lockers = new ConcurrentHashMap<>();

    private final PoolRepository repository;
    private final TableService tableService;
    private final int batchRows;
    private final boolean autoLoadPoolFromDB; // For auto load datapool from DB. For debug issues

    @Autowired
    public BaseLockerService(PoolRepository repository, TableService tableService,
                             @Value("${lockerService.batchRows}") int batchRows,
                             @Value("${lockerService.autoLoadPoolFromDB:false}") boolean autoLoadPoolFromDB
                             ) {
        this.repository = repository;
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
                repository.tables(schema).forEach(tableName -> {
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
        repository.schemas().forEach(schema -> {
            if (!systemSchemas.contains(schema)) schemas.add(schema);
        });
        return schemas;
    }

    private void selectLocksFromTable(String schema, String tableName) {
        final String fullTableName = tableService.fullName(schema, tableName);
        logger.info("Locker scan table: " + fullTableName);

        try {
            if (!containsColumns(schema, tableName))
                return;
            Locker locker;
            Integer maxRid = repository.maxRid(fullTableName);
            if (maxRid == null)
                locker = new BaseLocker(fullTableName);
            else
                locker = new BaseLocker(fullTableName, maxRid);

            lockers.put(fullTableName, locker);

            final Integer lockedRows = repository.lockedRows(fullTableName);
            if (lockedRows.equals(0))
                return;

            int after = 0;
            for (int rows = lockedRows; rows > 0; rows -= batchRows) {
                List<Integer> rids = repository.lockedRidsAfter(fullTableName, after, batchRows);
                if (rids.isEmpty()) break;
                rids.forEach(locker::lock);
                after = rids.get(rids.size() - 1);
            }
            logger.info("\tLockedRows: " + lockedRows);
        } catch (Exception e) {
            logger.error("Error scan table:" + fullTableName, e);
        }
    }

    private boolean containsColumns(String schema, String tableName) {
        return containsColumn(schema, tableName, "RID") && containsColumn(schema, tableName, "LOCKED");
    }

    private boolean containsColumn(String schema, String tableName, String column) {
        boolean contain = repository.hasColumn(schema, tableName, column);
        if (!contain)
            logger.error("Table " + tableService.fullName(schema, tableName) + " not contain " + column + " column");
        return contain;
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
                    repository.tables(schema).forEach(tableName -> {
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
        }
        return false;
    }

    @Override
    public boolean isMarkedAsEmpty(String env, String pool) {
        return locker(env, pool).isMarkedAsEmpty();
    }

    @Override
    public void markAsEmpty(String env, String pool) {
        locker(env, pool).markAsEmpty();
    }

    @Override
    public void markAsNotEmpty(String env, String pool) {
        locker(env, pool).markAsNotEmpty();
    }

    @Override
    public Locker getLocker(String env, String pool) {
        return locker(env, pool);
    }

    private Locker locker(String env, String pool) {
        return lockers.get(tableService.fullName(env, pool));
    }

    @Override
    public void putPool(String env, String pool) {
        lockers.put(tableService.fullName(env, pool), new BaseLocker(tableService.fullName(env, pool)));
    }

    @Override
    public void putPool(String env, String pool, int size) {
        String full = tableService.fullName(env, pool);
        lockers.put(full, new BaseLocker(full, size));
    }

    @Override
    public void deletePool(String env, String pool) {
        lockers.remove(tableService.fullName(env, pool));
    }

    @Override
    public void add(String env, String pool) {
        add(env, pool, 1);
    }

    @Override
    public void add(String env, String pool, int count) {
        Locker l = locker(env, pool);
        synchronized (l) {
            l.add(count);
            l.markAsNotEmpty();
        }
    }

    @Override
    public void lock(String env, String pool, int rid) {
        String full = tableService.fullName(env, pool);
        Locker l = lockers.get(full);
        synchronized (l) {
            repository.lock(full, rid);
            l.lock(rid);
        }
    }

    /** Атомарно занимает строку: true только если UPDATE действительно перевёл locked false -> true. */
    @Override
    public boolean tryLock(String env, String pool, int rid) {
        String full = tableService.fullName(env, pool);
        boolean locked = repository.tryLock(full, rid);
        if (locked) {
            Locker l = lockers.get(full);
            synchronized (l) { l.lock(rid); }
        }
        return locked;
    }

    @Override
    public void unlock(String env, String pool, int rid) {
        String full = tableService.fullName(env, pool);
        Locker l = lockers.get(full);
        synchronized (l) {
            repository.unlock(full, rid);
            l.unlock(rid);
            l.markAsNotEmpty();
        }
    }

    @Override
    public void unlock(String env, String pool, String searchKey) {
        String full = tableService.fullName(env, pool);
        Locker l = lockers.get(full);
        synchronized (l) {
            Integer rid = repository.unlockByKey(full, searchKey);
            if (rid != null) l.unlock(rid);
            l.markAsNotEmpty();
        }
    }

    @Override
    public void unlockAll(String env, String pool) {
        String full = tableService.fullName(env, pool);
        Locker l = lockers.get(full);
        synchronized (l) {
            repository.unlockAll(full);
            l.unlockAll();
            l.markAsNotEmpty();
        }
    }

    @Override
    public int firstUnlockRid(String env, String pool) {
        return locker(env, pool).firstUnlockId();
    }

    @Override
    public int firstBiggerUnlockedId(String env, String pool, int id) {
        return locker(env, pool).firstBiggerUnlockedId(id);
    }

    @Override
    public void ensureSize(String env, String pool, int size) {
        locker(env, pool).ensureSize(size);
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
            Integer maxRid = repository.maxRid(full);
            locker.reset(maxRid == null ? 0 : maxRid, repository.lockedRids(full));
        }
    }
}
