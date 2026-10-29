package load.datapool.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Кэш блокировок пула в памяти: list[rid - startIndex] == true, если строка занята. size — максимальный rid пула. */
public class BaseLocker implements Locker {
    private final Logger logger = LoggerFactory.getLogger(BaseLocker.class);

    private final String poolName;
    private boolean[] list;
    private int size = 0;
    private int bufferSize = 1000;
    public static final int startIndex = 1;
    private boolean markedAsEmpty = false; // Mark true if the pool has become empty. There is no need to check table in the DB
    private final AtomicLong cursorEpoch = new AtomicLong();
    private final ReentrantLock restartLock = new ReentrantLock();

    public BaseLocker(String poolName) {
        this.poolName = poolName;
        list = new boolean[bufferSize];
    }

    public BaseLocker(String poolName, int size) {
        this.poolName = poolName;
        this.size = size;
        bufferSize = Math.max(bufferSize, size / 10);
        list = new boolean[size + bufferSize];
        logger.debug("Create locker {}: size = {}, bufferSize = {}, total length = {}", this.poolName, this.size, bufferSize, list.length);
    }

    public String getPoolName() {
        return poolName;
    }

    @Override
    public synchronized int getSize() {
        return size;
    }

    @Override
    public synchronized void add() {
        add(1);
    }

    @Override
    public synchronized void add(int count) {
        ensureSize(size + count);
    }

    @Override
    public synchronized void ensureSize(int newSize) {
        if (newSize <= size) return;
        size = newSize;
        if (size >= list.length) {
            list = Arrays.copyOf(list, size + bufferSize);
        }
    }

    @Override
    public synchronized void reset(int newSize, List<Integer> lockedIds) {
        size = newSize;
        list = new boolean[size + bufferSize];
        for (int id : lockedIds) {
            if (id >= startIndex && id <= size) list[id - startIndex] = true;
        }
        markedAsEmpty = size == 0;
        cursorEpoch.incrementAndGet();
    }

    @Override
    public synchronized void lock(int id) {
        if (id < startIndex || id > list.length) return; // Some collision
        list[id - startIndex] = true;
    }

    @Override
    public synchronized void unlock(int id) {
        if (id < startIndex || id > list.length) return;
        list[id - startIndex] = false;
    }

    @Override
    public synchronized void unlockAll() {
        Arrays.fill(list, false);
    }

    @Override
    public synchronized int firstUnlockId() {
        for (int i = 0; i < size; i++) {
            if (!list[i]) {     // not locked
                return i + startIndex;
            }
        }
        return 0;  // null index
    }

    @Override
    public synchronized int firstBiggerUnlockedId(int id) {
        // rid r лежит в list[r - startIndex], поэтому индекс следующего за id rid равен id (при startIndex == 1)
        int from = id - startIndex + 1;
        if (from < 0) from = 0;
        for (int i = from; i < size; i++) {
            if (!list[i]) {
                return i + startIndex;
            }
        }
        return 0;
    }

    @Override
    public synchronized boolean isMarkedAsEmpty() {
        return this.markedAsEmpty;
    }

    @Override
    public synchronized void markAsEmpty() {
        if (this.markedAsEmpty) return;
        logger.warn("{} marked like empty pool.", poolName);
        this.markedAsEmpty = true;
    }

    @Override
    public synchronized void markAsNotEmpty() {
        if (!this.markedAsEmpty) return;
        logger.warn("{} marked like NOT empty pool.", poolName);
        this.markedAsEmpty = false;
    }

    @Override
    public synchronized int lockedCount() {
        int n = 0;
        for (boolean b : list) if (b) n++;
        return n;
    }

    @Override
    public synchronized List<Integer> lockedIds() {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < list.length; i++) if (list[i]) ids.add(i + startIndex);
        return ids;
    }

    @Override
    public long cursorEpoch() {
        return cursorEpoch.get();
    }

    @Override
    public void nextCursorEpoch() {
        cursorEpoch.incrementAndGet();
    }

    @Override
    public ReentrantLock getRestartLock() {
        return restartLock;
    }

}
