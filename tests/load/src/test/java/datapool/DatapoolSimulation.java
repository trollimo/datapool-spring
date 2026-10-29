package datapool;

import io.gatling.javaapi.core.ChainBuilder;
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
        if (POISON_RPS > 0) pops.add(POISON.injectOpen(rampUsersPerSec(0).to(POISON_RPS).during(WARM_SEC),
                constantUsersPerSec(POISON_RPS).during(STEPS * (LEVEL_SEC + RAMP_SEC))));
        setUp(pops).protocols(protocol).assertions(global().failedRequests().percent().lte(1.0));
    }

    @Override
    public void before() {
        Diag.info("params: rps=" + RPS + " step=" + STEP + " steps=" + STEPS + " levelSec=" + LEVEL_SEC + " rampSec=" + RAMP_SEC
                + " poisonRps=" + POISON_RPS + " searchW=" + SEARCH_W + " preLockPct=" + PRELOCK_PCT + " longPct=" + LONG_PCT + " holdMs=" + HOLD_MS);
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
