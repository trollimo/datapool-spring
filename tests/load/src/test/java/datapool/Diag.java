package datapool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Сводка по ходу теста: что выдал сервис, какие rid клиент считает занятыми, виды ошибочных ответов. */
public final class Diag {
    private Diag() {}

    private static final Map<Long, Long> HELD = new ConcurrentHashMap<>();           // rid -> момент блокировки (клиентская правда о p4)
    private static final AtomicLong DUP_LOCK = new AtomicLong();                      // одну занятую строку выдали второй раз
    private static final AtomicLong LOCKS = new AtomicLong();
    private static final Map<String, AtomicLong> ERRORS = new ConcurrentHashMap<>();  // «вид тела ответа» -> сколько
    private static final List<String> LOG = new ArrayList<>();
    private static final AtomicReferenceArray<String> KEYS = new AtomicReferenceArray<>(4096);
    private static final AtomicLong KEY_POS = new AtomicLong();

    public static synchronized void info(String s) {
        LOG.add(s);
        System.out.println("[diag] " + s);
    }

    public static void hold(long rid) {
        LOCKS.incrementAndGet();
        if (HELD.putIfAbsent(rid, System.currentTimeMillis()) != null) {
            if (DUP_LOCK.incrementAndGet() <= 20) info("DUPLICATE LOCK: rid " + rid + " выдан повторно, пока не разблокирован");
        }
    }

    private static final Map<String, Set<Long>> PRELOCKED = new ConcurrentHashMap<>();   // пул -> строки, занятые заранее и навсегда

    /** Строка занята заранее (до начала нагрузки) в пуле, у которого нет сценария блокировок. */
    public static void prelock(String pool, long rid) { PRELOCKED.computeIfAbsent(pool, k -> ConcurrentHashMap.newKeySet()).add(rid); }

    public static Set<Long> prelocked(String pool) { return PRELOCKED.getOrDefault(pool, Set.of()); }

    public static void release(long rid) { HELD.remove(rid); }

    public static int heldCount() { return HELD.size(); }

    public static Set<Long> heldSet() { return Set.copyOf(HELD.keySet()); }

    public static long dupLocks() { return DUP_LOCK.get(); }

    /** Вызывается для каждого ответа: запоминает только «странные» (без rid), приводя числа к # чтобы виды сливались. */
    public static int classify(String pool, String body) {
        if (body == null || body.contains("\"rid\":") || body.startsWith("1 Inserted") || body.startsWith("Unlocked")) return 0;
        String k = pool + " :: " + body.replaceAll("[0-9]+", "#").replace('\n', ' ');
        ERRORS.computeIfAbsent(k.substring(0, Math.min(k.length(), 200)), x -> new AtomicLong()).incrementAndGet();
        return 1;
    }

    public static void publishKey(String key) { KEYS.set((int) (KEY_POS.getAndIncrement() & 4095), key); }

    public static long putsOk() { return KEY_POS.get(); }

    public static String randomKey() {
        long n = Math.min(KEY_POS.get(), 4096);
        return n == 0 ? "" : KEYS.get(ThreadLocalRandom.current().nextInt((int) n));
    }

    public static String summary() {
        StringBuilder sb = new StringBuilder("=== datapool diagnostics ===\n");
        sb.append("locks issued (p4): ").append(LOCKS.get()).append(", held now: ").append(HELD.size())
                .append(", duplicate locks: ").append(DUP_LOCK.get()).append('\n');
        sb.append("non-OK response kinds:\n");
        ERRORS.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .forEach(e -> sb.append("  ").append(e.getValue().get()).append("  ").append(e.getKey()).append('\n'));
        for (String l : LOG) sb.append(l).append('\n');
        return sb.toString();
    }

    public static void write(String file) {
        try {
            Path p = Path.of(file);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, summary());
        } catch (IOException e) {
            System.out.println("cannot write " + file + ": " + e);
        }
    }
}
