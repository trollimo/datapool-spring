package load.datapool.service;

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

    Object getLocker(String env, String pool);
}
