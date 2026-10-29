package load.datapool.service;

import java.util.Map;

public interface LockerService {

    void putPool(String env, String pool);
    void putPool(String env, String pool, int size);
    void deletePool(String env, String pool);
    void add(String env, String pool);
    void add(String env, String pool, int count);
    void lock(String env, String pool, int rid);
    boolean tryLock(String env, String pool, int rid);
    void unlock(String env, String pool, int rid);
    void unlock(String env, String pool, String searchKey);
    void unlockAll(String env, String pool);
    int firstUnlockRid(String env, String pool);
    int firstBiggerUnlockedId(String env, String pool, int id);
    void initLocks();
    boolean poolExist(String env, String pool);
    boolean isMarkedAsEmpty(String env, String pool);
    void markAsEmpty(String env, String pool);
    void markAsNotEmpty(String env, String pool);

    Locker getLocker(String env, String pool);
    void ensureSize(String env, String pool, int size);
    /** Снимок реестра: полное имя пула (ENV.POOL) -> локер. */
    Map<String, Locker> allLockers();
    /** Пересобирает кэш пула из БД: размер = max(rid), locked из таблицы. */
    void reloadFromDb(String env, String pool);
}
