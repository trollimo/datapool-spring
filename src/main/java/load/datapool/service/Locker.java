package load.datapool.service;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public interface Locker {

    void add();
    void add(int count);
    /** Размер кэша должен быть не меньше rid последней строки пула (rid берётся из sequence, поэтому может быть больше количества строк). */
    void ensureSize(int size);
    int getSize();
    /** Полная пересборка кэша: размер = maxRid, заняты ровно lockedIds. Используется при исправлении расхождения. */
    void reset(int size, List<Integer> lockedIds);
    void lock(int id);
    void unlock(int id);
    void unlockAll();
    int firstUnlockId();
    /** Первый незаблокированный rid строго больше id; 0, если такого нет. */
    int firstBiggerUnlockedId(int id);
    boolean isMarkedAsEmpty();
    void markAsEmpty();
    void markAsNotEmpty();

    /** Сколько rid помечено заблокированными в кэше. */
    int lockedCount();
    /** rid, помеченные заблокированными в кэше (для диагностики). */
    List<Integer> lockedIds();

    /** Номер «эпохи» курсора: растёт после каждого сдвига sequence. Нужен, чтобы запоздавшие потоки не откатывали курсор назад. */
    long cursorEpoch();
    void nextCursorEpoch();
    /** Один поток за раз сдвигает курсор пула. */
    ReentrantLock getRestartLock();

}
