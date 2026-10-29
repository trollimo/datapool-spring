package datapool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Синхронные вызовы API датапула вне нагрузки: подготовка пулов до теста и проверка пулов после. */
public final class Api {
    private Api() {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Pattern RID = Pattern.compile("\"rid\":(\\d+)");

    public record Resp(int status, String body) {}

    public static Resp call(String method, String pathAndQuery, byte[] body, String contentType, int timeoutSec) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(Cfg.BASE + pathAndQuery)).timeout(Duration.ofSeconds(timeoutSec));
            if (contentType != null) b.header("Content-Type", contentType);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body());
        } catch (Exception e) {
            return new Resp(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public static Resp get(String path) { return call("GET", path, null, null, 10); }

    public static Resp post(String path) { return call("POST", path, null, null, 30); }

    public static String csv(String pool, int rows) {
        StringBuilder sb = new StringBuilder("id,login,token\n");
        for (int i = 1; i <= rows; i++) sb.append(i).append(',').append(pool).append('u').append(i).append(",tok").append(i).append('\n');
        return sb.toString();
    }

    /** Загрузка CSV в пул с пересозданием (как делает JMeter-прототип: upload-csv-as-json&override=true). */
    public static Resp upload(String pool, int rows) {
        String boundary = "----dp" + System.nanoTime();
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + pool + ".csv\"\r\nContent-Type: text/csv\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] body = (head + csv(pool, rows) + tail).getBytes(StandardCharsets.UTF_8);
        return call("POST", "/api/v1/upload-csv-as-json?env=" + Cfg.ENV + "&pool=" + pool + "&override=true&delimiter=,", body,
                "multipart/form-data; boundary=" + boundary, 120);
    }

    public static Resp resetSeq(String pool) { return get("/api/v1/resetseq?env=" + Cfg.ENV + "&pool=" + pool); }

    public static Resp next(String pool, boolean locked) {
        return get("/api/v1/get-next-value?env=" + Cfg.ENV + "&pool=" + pool + "&locked=" + locked);
    }

    public static Resp unlock(String pool, long rid) { return post("/api/v1/unlock?env=" + Cfg.ENV + "&pool=" + pool + "&rid=" + rid); }

    public static Resp unlockAll(String pool) { return post("/api/v1/unlock?env=" + Cfg.ENV + "&pool=" + pool + "&unlock-all=true"); }

    public static long ridOf(String body) {
        Matcher m = RID.matcher(body);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** Подготовка: свежие пулы, resetseq, у блокирующего пула часть строк занята «навсегда» и разбросана по всему пулу. */
    public static void prepare(int preLockPct) {
        for (String p : Cfg.READ_POOLS) Diag.info("upload " + p + ": " + upload(p, Cfg.POOL_SIZE));
        Diag.info("upload " + Cfg.LOCK_POOL + ": " + upload(Cfg.LOCK_POOL, Cfg.POOL_SIZE));
        Diag.info("upload " + Cfg.MIX_POOL + ": " + upload(Cfg.MIX_POOL, Cfg.MIX_SEED));
        if (preLockPct > 0) {
            // берём все строки блокирующего пула и возвращаем обратно случайные (100-preLockPct)% — остальные остаются занятыми
            Random rnd = new Random(42);
            List<Long> all = new ArrayList<>();
            for (int i = 0; i < Cfg.POOL_SIZE; i++) {
                Resp r = next(Cfg.LOCK_POOL, true);
                long rid = ridOf(r.body());
                if (r.status() != 200 || rid < 0) { Diag.info("prelock stop at " + i + ": " + r); break; }
                all.add(rid);
            }
            for (long rid : all) {
                if (rnd.nextInt(100) >= preLockPct) unlock(Cfg.LOCK_POOL, rid);
                else Diag.hold(rid);
            }
            Diag.info("prelocked rows: " + Diag.heldCount() + " of " + all.size());
        }
        // служебные пулы для «человеческих» и MCP-ручек: тесты p1..p5 они не трогают
        Diag.info("upload h1: " + upload("h1", 100));
        Diag.info("upload h2: " + upload("h2", 200));
        Diag.info("upload h5: " + upload("h5", 50));
        for (String p : Cfg.READ_POOLS) resetSeq(p);
        resetSeq(Cfg.LOCK_POOL);
        resetSeq(Cfg.MIX_POOL);
    }

    public record Audit(String pool, int calls, int distinct, int errors, int dupInCycle, Set<Long> missing, Set<Long> unexpected, String firstError, long ms) {}

    /**
     * Один полный цикл чтения: делаем до limit вызовов get-next-value (locked=false) и смотрим, какие rid выданы.
     * Ждём, что выданы ровно все незанятые rid из 1..size. held — строки, которые клиент считает занятыми.
     */
    public static Audit cycle(String pool, int size, Set<Long> held, int limit) {
        long t0 = System.currentTimeMillis();
        Set<Long> seen = new HashSet<>();
        int calls = 0, errors = 0, dup = 0;
        String firstError = null;
        while (calls < limit) {
            calls++;
            Resp r = next(pool, false);
            if (r.status() != 200) {
                errors++;
                if (firstError == null) firstError = r.status() + " " + r.body().replace('\n', ' ');
                if (errors > 20) break;
                continue;
            }
            long rid = ridOf(r.body());
            if (!seen.add(rid)) { dup++; break; }              // круг замкнулся
            if (seen.size() >= size - held.size()) break;       // выдали всё, что должно быть свободно
        }
        Set<Long> missing = new HashSet<>(), unexpected = new HashSet<>();
        for (long i = 1; i <= size; i++) if (!held.contains(i) && !seen.contains(i)) missing.add(i);
        for (long rid : seen) if (held.contains(rid) || rid < 1 || rid > size) unexpected.add(rid);
        return new Audit(pool, calls, seen.size(), errors, dup, missing, unexpected, firstError, System.currentTimeMillis() - t0);
    }
}
