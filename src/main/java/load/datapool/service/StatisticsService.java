package load.datapool.service;

import load.datapool.prometheus.Exporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * История для страницы статистики: раз в interval секунд снимок счётчиков Prometheus и кэша блокировок, хранится window минут в памяти.
 * Снимок читает коллекторы напрямую и счётчики кэша (O(1)), в горячий путь ничего не добавляется. После перезапуска история пустая.
 */
@Service
public class StatisticsService {

    private final Logger logger = LoggerFactory.getLogger(StatisticsService.class);
    private final Exporter exporter;
    private final LockerService lockerService;

    @Value("${datapool.statistics.interval-seconds:15}")
    private int intervalSeconds;
    @Value("${datapool.statistics.window-minutes:60}")
    private int windowMinutes;

    /** Точка истории: значения за интервал, по пулам. */
    static final class Point {
        final long time;
        final Map<String, double[]> pools = new HashMap<>();   // rps, latencyMs (NaN — запросов не было), errorsPerSec, lockedPct, size, loops/с (сдвиги курсора), loopFails (за интервал)
        Point(long time) { this.time = time; }
    }

    private final Deque<Point> history = new ArrayDeque<>();
    private Map<String, Exporter.Totals> previous;
    private long previousTime;
    private ScheduledExecutorService scheduler;

    @Autowired
    public StatisticsService(Exporter exporter, LockerService lockerService) {
        this.exporter = exporter;
        this.lockerService = lockerService;
    }

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "statistics-sampler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                sample(System.currentTimeMillis());
            } catch (Exception e) {
                logger.error("Statistics sample failed", e);
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    public int getIntervalSeconds() { return intervalSeconds; }

    /** Снимок в момент now (мс). Первый вызов только запоминает счётчики. Публичный для тестов. */
    public synchronized void sample(long now) {
        Map<String, Exporter.Totals> totals = exporter.requestTotals();
        Map<String, Locker> lockers = lockerService.allLockers();
        if (previous != null && now > previousTime) {
            double seconds = (now - previousTime) / 1000.0;
            Point p = new Point(now);
            Set<String> names = new TreeSet<>(totals.keySet());
            names.addAll(lockers.keySet());
            for (String name : names) {
                Exporter.Totals cur = totals.get(name);
                Exporter.Totals prev = previous.get(name);
                double rps = 0, latencyMs = Double.NaN, errors = 0, loops = 0, loopFails = 0;
                if (cur != null) {
                    long dReq = Math.max(0, cur.requests - (prev == null ? 0 : prev.requests));
                    long dErr = Math.max(0, cur.errors - (prev == null ? 0 : prev.errors));
                    long dLat = Math.max(0, cur.latencyMicros - (prev == null ? 0 : prev.latencyMicros));
                    rps = dReq / seconds;
                    loops = Math.max(0, cur.retries - (prev == null ? 0 : prev.retries)) / seconds;
                    loopFails = Math.max(0, cur.loopFails - (prev == null ? 0 : prev.loopFails));
                    errors = dErr / seconds;
                    if (dReq > 0) latencyMs = dLat / 1000.0 / dReq;
                }
                double lockedPct = Double.NaN, size = Double.NaN;
                Locker l = lockers.get(name);
                if (l != null) {
                    size = l.getSize();
                    lockedPct = size > 0 ? 100.0 * l.lockedCount() / size : 0;
                }
                p.pools.put(name, new double[]{rps, latencyMs, errors, lockedPct, size, loops, loopFails});
            }
            history.addLast(p);
            int max = Math.max(1, windowMinutes * 60 / Math.max(1, intervalSeconds));
            while (history.size() > max) history.removeFirst();
        }
        previous = totals;
        previousTime = now;
    }

    /** Ряды для страницы за последние minutes минут: время и по пулам rps, latencyMs, errors, lockedPct, size (null — нет данных). */
    public synchronized Map<String, Object> series(int minutes) {
        long from = System.currentTimeMillis() - Math.max(1, Math.min(minutes, windowMinutes)) * 60_000L;
        List<Point> pts = new ArrayList<>();
        for (Point p : history) if (p.time >= from) pts.add(p);
        Set<String> names = new TreeSet<>();
        for (Point p : pts) names.addAll(p.pools.keySet());

        List<Long> times = new ArrayList<>();
        for (Point p : pts) times.add(p.time);
        Map<String, Object> poolsOut = new LinkedHashMap<>();
        String[] keys = {"rps", "latencyMs", "errors", "lockedPct", "size", "loops", "loopFails"};
        for (String name : names) {
            Map<String, List<Double>> one = new LinkedHashMap<>();
            for (String k : keys) one.put(k, new ArrayList<>());
            for (Point p : pts) {
                double[] v = p.pools.get(name);
                for (int i = 0; i < keys.length; i++) {
                    Double x = v == null || Double.isNaN(v[i]) ? null : Math.round(v[i] * 100.0) / 100.0;
                    one.get(keys[i]).add(x);
                }
            }
            poolsOut.put(name, one);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("intervalSec", intervalSeconds);
        res.put("windowMinutes", windowMinutes);
        res.put("timestamps", times);
        res.put("pools", poolsOut);
        return res;
    }
}
