package load.datapool.service;

import load.datapool.db.H2Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/** Диагностика пулов (счётчики в БД и в локере) и исправление расхождения без изменения схемы БД. */
@Service
public class PoolDiagnosticsService {

    private final Logger logger = LoggerFactory.getLogger(PoolDiagnosticsService.class);
    private final H2Template jdbc;
    private final LockerService lockerService;
    private final SequenceService sequences;

    @Autowired
    public PoolDiagnosticsService(H2Template jdbc, LockerService lockerService, SequenceService sequences) {
        this.jdbc = jdbc;
        this.lockerService = lockerService;
        this.sequences = sequences;
    }

    /** Все пулы; первыми те, у которых счётчики в БД и в локере расходятся. */
    public List<Map<String, Object>> listPools() {
        List<Map<String, Object>> res = new ArrayList<>();
        for (Map.Entry<String, Locker> e : lockerService.allLockers().entrySet()) {
            try {
                res.add(describe(e.getKey(), e.getValue()));
            } catch (Exception ex) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pool", e.getKey());
                m.put("diverged", true);
                m.put("error", String.valueOf(ex.getMessage()));
                res.add(m);
            }
        }
        res.sort((a, b) -> Boolean.compare(isTrue(b.get("diverged")), isTrue(a.get("diverged"))));
        return res;
    }

    private boolean isTrue(Object o) {
        return Boolean.TRUE.equals(o);
    }

    private Map<String, Object> describe(String full, Locker locker) {
        Integer maxRid = jdbc.queryForObject("SELECT max(rid) FROM " + full, Integer.class);
        Integer rows = jdbc.queryForObject("SELECT count(rid) FROM " + full, Integer.class);
        Set<Integer> dbLocked = new HashSet<>(jdbc.queryForList("SELECT rid FROM " + full + " WHERE locked = true", Integer.class));
        Set<Integer> cacheLocked = new HashSet<>(locker.lockedIds());
        int max = maxRid == null ? 0 : maxRid;
        int count = rows == null ? 0 : rows;

        Set<Integer> onlyDb = new TreeSet<>(dbLocked);
        onlyDb.removeAll(cacheLocked);
        Set<Integer> onlyCache = new TreeSet<>(cacheLocked);
        onlyCache.removeAll(dbLocked);
        boolean sizeDiff = locker.getSize() != max;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pool", full);
        m.put("diverged", sizeDiff || !onlyDb.isEmpty() || !onlyCache.isEmpty());
        m.put("lockerSize", locker.getSize());
        m.put("dbMaxRid", max);
        m.put("dbRows", count);
        m.put("holes", max - count);
        m.put("dbLocked", dbLocked.size());
        m.put("lockerLocked", cacheLocked.size());
        m.put("lockedOnlyInDb", onlyDb.size());
        m.put("lockedOnlyInLocker", onlyCache.size());
        m.put("empty", locker.isMarkedAsEmpty());
        m.put("cursor", cursor(full));
        m.put("cursorEpoch", locker.cursorEpoch());
        return m;
    }

    private Long cursor(String full) {
        String[] p = split(full);
        return sequences.currentRid(p[0], p[1]);
    }

    /** Исправляет один пул: пересобирает кэш из БД и ставит курсор на первую свободную строку. */
    public Map<String, Object> repair(String env, String pool) {
        Map<String, Object> m = new LinkedHashMap<>();
        String full = (env + "." + pool).toUpperCase();
        m.put("pool", full);
        if (!lockerService.poolExist(env, pool)) {
            m.put("ok", false);
            m.put("message", "Pool not found");
            return m;
        }
        Locker locker = (Locker) lockerService.getLocker(env, pool);
        locker.getRestartLock().lock();
        try {
            lockerService.reloadFromDb(env, pool);
            int first = locker.firstUnlockId();
            if (first == 0) locker.markAsEmpty();
            sequences.restartRid(env, pool, first == 0 ? 1 : first);
            m.put("ok", true);
            m.put("lockerSize", locker.getSize());
            m.put("cursor", first == 0 ? 1 : first);
            logger.info("Pool {} repaired: size={}, cursor={}", full, locker.getSize(), first);
        } catch (Exception e) {
            logger.error("Repair failed for " + full, e);
            m.put("ok", false);
            m.put("message", String.valueOf(e.getMessage()));
        } finally {
            locker.getRestartLock().unlock();
        }
        return m;
    }

    /** Массовое исправление; pools — полные имена ENV.POOL. */
    public List<Map<String, Object>> repairAll(Collection<String> pools) {
        List<Map<String, Object>> res = new ArrayList<>();
        for (String full : pools) {
            String[] p = split(full);
            res.add(repair(p[0], p[1]));
        }
        return res;
    }

    private String[] split(String full) {
        int i = full.indexOf('.');
        return new String[]{full.substring(0, i), full.substring(i + 1)};
    }
}
