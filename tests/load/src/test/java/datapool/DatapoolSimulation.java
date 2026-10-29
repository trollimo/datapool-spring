package datapool;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.http.HttpRequestActionBuilder;
import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.StringBodyPart;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Нагрузка на датапул, 5 пулов:
 * p1..p3 — только чтение (locked=false);
 * p4 — чтение с блокировкой (locked=true), удержание, потом unlock по rid; часть строк занята заранее и разбросана по пулу,
 *      небольшая доля «долгих» блокировок — так курсор вынужден обходить занятые строки;
 * p5 — одновременно вставка (put-value), чтение (get-next-value) и поиск по ключу (search-by-key) разными сценариями.
 * Доли считаются от общего числа http-запросов в секунду (R): p1..p3 по 15%, p4 20% (2 запроса на итерацию), p5: put 15%, get 15%, search 5%.
 *
 * Параметры (-D): rps=1000 (старт), step=500, steps=1, levelSec=300, rampSec=10, warmSec=30, preLockPct=30, longPct=3, holdMs=1000.
 */
public class DatapoolSimulation extends Simulation {

    static final double RPS = Cfg.dbl("rps", 1000);
    static final double STEP = Cfg.dbl("step", 500);
    static final int STEPS = Cfg.num("steps", 1);
    static final int LEVEL_SEC = Cfg.num("levelSec", 300);
    static final int RAMP_SEC = Cfg.num("rampSec", 10);
    static final int WARM_SEC = Cfg.num("warmSec", 30);
    static final int PRELOCK_PCT = Cfg.num("preLockPct", 30);
    static final int LONG_PCT = Cfg.num("longPct", 3);
    static final int HOLD_MS = Cfg.num("holdMs", 1000);
    static final double POISON_RPS = Cfg.dbl("poisonRps", 0);        // сколько «ядовитых» вставок в секунду (0 — выключено)
    static final double SEARCH_W = Cfg.dbl("searchW", 0.05);     // доля search-by-key в потоке запросов; 0 — выключить трек

    // реюс соединений: один пул соединений на всех виртуальных пользователей, иначе в открытой модели каждый пользователь = новое соединение
    HttpProtocolBuilder protocol = http.baseUrl(Cfg.BASE)
            .shareConnections()
            .disableWarmUp()
            .acceptHeader("*/*")
            .userAgentHeader("gatling-datapool");

    static ChainBuilder read(String pool) {
        return exec(http("get-next " + pool).get("/api/v1/get-next-value?pool=" + pool + "&locked=false")
                .check(status().is(200))
                .check(regex("\"login\":\"(p\\d)u").is(pool))             // выдана строка именно этого пула
                .check(bodyString().transform(b -> Diag.classify(pool, b)).exists()));
    }

    static final ScenarioBuilder LOCK = scenario("lock p4")
            .exec(http("lock-get p4").get("/api/v1/get-next-value?pool=p4&locked=true")
                    .check(status().is(200))
                    .check(regex("\"rid\":(\\d+)").saveAs("rid"))
                    .check(bodyString().transform(b -> Diag.classify("p4", b)).exists()))
            .exitHereIfFailed()
            .exec(session -> {
                Diag.hold(Long.parseLong(session.getString("rid")));
                boolean longHold = ThreadLocalRandom.current().nextInt(100) < LONG_PCT;
                int ms = longHold ? 20_000 + ThreadLocalRandom.current().nextInt(40_000) : HOLD_MS / 5 + ThreadLocalRandom.current().nextInt(HOLD_MS * 3 / 2);
                return session.set("holdMs", ms);
            })
            .pause(session -> Duration.ofMillis(session.getInt("holdMs")))
            .exec(session -> { Diag.release(Long.parseLong(session.getString("rid"))); return session; })
            .exec(http("unlock p4").post("/api/v1/unlock?pool=p4&rid=#{rid}")
                    .check(status().is(200))
                    .check(bodyString().transform(b -> Diag.classify("p4", b)).exists()));

    static final ScenarioBuilder PUT = scenario("put p5")
            .exec(session -> session.set("key", UUID.randomUUID().toString().replace("-", "")))
            .exec(http("put p5").post("/api/v1/put-value?pool=p5&search-key=#{key}")
                    .header("Content-Type", "text/plain")
                    .body(StringBody("{\"id\":\"#{key}\",\"login\":\"p5u#{key}\",\"token\":\"t\"}"))
                    .check(status().is(200))
                    .check(bodyString().transform(b -> Diag.classify("p5", b)).exists()))
            .doIf(session -> !session.isFailed()).then(exec(session -> { Diag.publishKey(session.getString("key")); return session; }));

    static final String BIG = "x".repeat(33_000);

    /** Вставка, которая падает на длине текста: nextval(_max) уже взят и не откатывается, в таблице остаётся дыра в rid. */
    static final ScenarioBuilder POISON = scenario("poison put p5")
            .exec(http("poison put p5").post("/api/v1/put-value?pool=p5&search-key=poison")
                    .header("Content-Type", "text/plain")
                    .body(StringBody(BIG))
                    .check(status().is(400)));          // 400 здесь ожидаем: это намеренный сбой, он не считается ошибкой теста

    static final ScenarioBuilder SEARCH = scenario("search p5")
            .exec(session -> session.set("skey", Diag.randomKey()))
            .doIf(session -> !session.getString("skey").isEmpty()).then(
                    exec(http("search-by-key p5").get("/api/v1/search-by-key?pool=p5&locked=false&search-key=#{skey}")
                            .check(status().is(200))
                            .check(substring("#{skey}"))
                            .check(bodyString().transform(b -> Diag.classify("p5", b)).exists())));


    // ---- «человеческие» ручки и MCP: редкие (по умолчанию 2 раза в минуту каждая), на служебных пулах h1..h7 ----

    static String rpc(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\"" + (params == null ? "" : ",\"params\":" + params) + "}";
    }

    static HttpRequestActionBuilder mcp(String name, String body) {
        return http("mcp " + name).post("/mcp").header("Content-Type", "application/json").body(StringBody(body)).check(status().is(200));
    }

    /** Вызов тулы MCP: ответ 200 и result.isError == false (rate limited / busy — это провал). */
    static HttpRequestActionBuilder tool(String tool, String args) {
        return mcp(tool, rpc("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + args + "}"))
                .check(jsonPath("$.result.isError").ofBoolean().is(false));
    }

    static final List<ScenarioBuilder> REST_HUMAN = List.of(
            scenario("human pools list").exec(http("human GET pools").get("/api/v1/pools").check(status().is(200)).check(jsonPath("$[0].pool").exists())),
            scenario("human pools page").exec(http("human GET pools.html").get("/pools.html").check(status().is(200)).check(substring("drop-pool"))),
            scenario("human metrics").exec(http("human GET metrics").get("/metrics").check(status().is(200))),
            scenario("human repair h1").exec(http("human POST pools/repair").post("/api/v1/pools/repair?env=load&pool=h1").check(status().is(200)).check(jsonPath("$.ok").ofBoolean().is(true))),
            scenario("human repair-batch").exec(http("human POST pools/repair-batch").post("/api/v1/pools/repair-batch").header("Content-Type", "application/json")
                    .body(StringBody("[\"LOAD.H1\",\"LOAD.H2\"]")).check(status().is(200)).check(jsonPath("$[1].ok").ofBoolean().is(true))),
            scenario("human resetseq h1").exec(http("human GET resetseq").get("/api/v1/resetseq?env=load&pool=h1").check(status().is(200))),
            scenario("human unlock h1").exec(http("human POST unlock").post("/api/v1/unlock?env=load&pool=h1&unlock-all=true").check(status().is(200))),
            scenario("human download h2").exec(http("human GET download/csv").get("/api/v1/download/csv?env=load&pool=h2&delimiter=;").check(status().is(200)).check(substring("login"))),
            scenario("human upload h7").exec(http("human POST upload-csv").post("/api/v1/upload-csv-as-json?env=load&pool=h7&override=true&delimiter=;")
                    .bodyPart(StringBodyPart("file", "a;b\n1;2\n3;4\n").fileName("x.csv")).check(status().is(200))),
            scenario("human put+drop h3").exec(http("human put h3").post("/api/v1/put-value?env=load&pool=h3").header("Content-Type", "text/plain").body(StringBody("{\"a\":\"1\"}")).check(status().is(200)))
                    .exec(http("human DELETE drop-pool").delete("/api/v1/drop-pool?env=load&pool=h3").check(status().is(200))));

    static final List<ScenarioBuilder> MCP_AI = List.of(
            scenario("ai session").exec(mcp("initialize", rpc("initialize", "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"gatling\",\"version\":\"1\"}}"))
                            .check(jsonPath("$.result.serverInfo.name").is("datapool")))
                    .exec(mcp("tools/list", rpc("tools/list", null)).check(jsonPath("$.result.tools[*].name").findAll().transform(l -> l.size()).is(10)))
                    .exec(tool("about", "{}")),
            scenario("ai list_pools").exec(tool("list_pools", "{}")),
            scenario("ai counters_stats").exec(tool("counters_stats", "{}")),
            scenario("ai repair_pools").exec(tool("repair_pools", "{\"pools\":[\"LOAD.H1\"]}")),
            scenario("ai reset_sequence").exec(tool("reset_sequence", "{\"env\":\"load\",\"pool\":\"h1\"}")),
            scenario("ai unlock").exec(tool("unlock", "{\"env\":\"load\",\"pool\":\"h1\",\"rid\":1}")),
            scenario("ai put_value").exec(tool("put_value", "{\"env\":\"load\",\"pool\":\"h4\",\"value\":\"{\\\"a\\\":\\\"1\\\"}\"}")),
            scenario("ai upload_csv").exec(tool("upload_csv", "{\"env\":\"load\",\"pool\":\"h5\",\"csv\":\"login,x\\nu1,1\\nu2,2\\n\",\"override\":true}")),
            scenario("ai download_pool_csv").exec(tool("download_pool_csv", "{\"env\":\"load\",\"pool\":\"h5\"}")),
            scenario("ai put+drop h6").exec(http("ai put h6").post("/api/v1/put-value?env=load&pool=h6").header("Content-Type", "text/plain").body(StringBody("{\"a\":\"1\"}")).check(status().is(200)))
                    .exec(tool("drop_pool", "{\"env\":\"load\",\"pool\":\"h6\",\"confirm\":\"LOAD.H6\"}")));

    /** Редкая ручка: perMin запросов в минуту на всё время теста; offset разносит ручки по времени, чтобы тяжёлые не совпадали. */
    private static PopulationBuilder rare(ScenarioBuilder sc, double perMin, int index) {
        int total = WARM_SEC + STEPS * (LEVEL_SEC + RAMP_SEC);
        return sc.injectOpen(nothingFor(Duration.ofSeconds(WARM_SEC / 2 + (index * 7L) % 30)),
                constantUsersPerSec(perMin / 60.0).during(Duration.ofSeconds(total)));
    }

    private OpenInjectionStep[] profile(double w) {
        return new OpenInjectionStep[]{
                rampUsersPerSec(0).to(w * RPS).during(WARM_SEC),
                incrementUsersPerSec(w * STEP).times(STEPS).eachLevelLasting(LEVEL_SEC).separatedByRampsLasting(RAMP_SEC).startingFrom(w * RPS)
        };
    }

    {
        List<PopulationBuilder> pops = new ArrayList<>(List.of(
                scenario("read p1").exec(read("p1")).injectOpen(profile(0.15)),
                scenario("read p2").exec(read("p2")).injectOpen(profile(0.15)),
                scenario("read p3").exec(read("p3")).injectOpen(profile(0.15)),
                LOCK.injectOpen(profile(0.10)),                       // 2 запроса на итерацию = 20% от R
                PUT.injectOpen(profile(0.15)),
                scenario("get p5").exec(read("p5")).injectOpen(profile(0.15))));
        if (SEARCH_W > 0) pops.add(SEARCH.injectOpen(profile(SEARCH_W)));
        if (Cfg.HUMAN_PER_MIN > 0) for (int i = 0; i < REST_HUMAN.size(); i++) pops.add(rare(REST_HUMAN.get(i), Cfg.HUMAN_PER_MIN, i));
        if (Cfg.AI_PER_MIN > 0) for (int i = 0; i < MCP_AI.size(); i++) pops.add(rare(MCP_AI.get(i), Cfg.AI_PER_MIN, i + REST_HUMAN.size()));
        if (POISON_RPS > 0) pops.add(POISON.injectOpen(rampUsersPerSec(0).to(POISON_RPS).during(WARM_SEC),
                constantUsersPerSec(POISON_RPS).during(STEPS * (LEVEL_SEC + RAMP_SEC))));
        setUp(pops).protocols(protocol).assertions(global().failedRequests().percent().lte(1.0));
    }

    @Override
    public void before() {
        Diag.info("params: rps=" + RPS + " step=" + STEP + " steps=" + STEPS + " levelSec=" + LEVEL_SEC + " rampSec=" + RAMP_SEC
                + " humanPerMin=" + Cfg.HUMAN_PER_MIN + " aiPerMin=" + Cfg.AI_PER_MIN + " poisonRps=" + POISON_RPS + " searchW=" + SEARCH_W + " preLockPct=" + PRELOCK_PCT + " longPct=" + LONG_PCT + " holdMs=" + HOLD_MS);
        Api.prepare(PRELOCK_PCT);
    }

    @Override
    public void after() {
        // пользователи уже закончили. Проверяем, что после нагрузки каждый пул обходится полным кругом
        List<Api.Audit> audits = new ArrayList<>();
        for (String p : Cfg.READ_POOLS) audits.add(Api.cycle(p, Cfg.POOL_SIZE, java.util.Set.of(), Cfg.POOL_SIZE + 200));
        audits.add(Api.cycle(Cfg.LOCK_POOL, Cfg.POOL_SIZE, Diag.heldSet(), Cfg.POOL_SIZE + 200));
        // p5: после нагрузки один клиент обходит пул; дыра в rid (упавшая вставка) превращает чтение в цикл/StackOverflow
        audits.add(Api.cycle(Cfg.MIX_POOL, (int) (Cfg.MIX_SEED + Diag.putsOk()), java.util.Set.of(), (int) (Cfg.MIX_SEED + Diag.putsOk()) + 50));
        audits.forEach(DatapoolSimulation::report);

        // вердикт регрессии: дублей блокировок нет, обход каждого пула без ошибок, в /api/v1/pools нет расходящихся пулов (версия 1.8.0+)
        List<String> fails = new ArrayList<>();
        if (Diag.dupLocks() > 0) fails.add("duplicate locks: " + Diag.dupLocks());
        for (Api.Audit a : audits) if (a.errors() > 0) fails.add(a.pool() + ": " + a.errors() + " errors, first: " + a.firstError());
        Api.Resp pools = Api.get("/api/v1/pools");
        if (pools.status() == 200) {
            if (pools.body().contains("\"diverged\":true")) fails.add("/api/v1/pools reports diverged pools");
        } else {
            Diag.info("/api/v1/pools is not available (status " + pools.status() + "), divergence check skipped");
        }
        Diag.info(fails.isEmpty() ? "VERDICT: PASS" : "VERDICT: FAIL " + fails);
        Diag.write("target/datapool-diag.txt");
        System.out.println(Diag.summary());
    }

    private static void report(Api.Audit a) {
        Diag.info(String.format("AUDIT %s: calls=%d distinct=%d errors=%d cycleClosed=%d missing=%d unexpected=%d %dms%s",
                a.pool(), a.calls(), a.distinct(), a.errors(), a.dupInCycle(), a.missing().size(), a.unexpected().size(), a.ms(),
                a.firstError() == null ? "" : " firstError=" + a.firstError()));
    }
}
