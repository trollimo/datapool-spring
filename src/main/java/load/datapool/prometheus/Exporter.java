package load.datapool.prometheus;

import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.common.TextFormat;
import load.datapool.db.H2Template;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class Exporter {

    @Autowired
    private H2Template jdbcOperations;

    // Prometheus collectors
    static final CollectorRegistry c = new CollectorRegistry();
    static private String [] labelNames= new String [] {"host", "port", "environment","name"};
    static private String [] labelNamesExt= new String [] {"host", "port", "environment","name","method", "details"};

    static final Gauge availableRows = Gauge.build()
            .name("datapool_available_rows")
            .labelNames(labelNames)
            .help("Available no locked rows count.").register(c);

    static final Gauge totalRows = Gauge.build()
            .name("datapool_total_rows")
            .labelNames(labelNames)
            .help("Total rows count.").register(c);

    static final Gauge currentOffset = Gauge.build()
            .name("datapool_current_offset")
            .labelNames(labelNames)
            .help("Current sequence position.").register(c);

    static final Counter latency = Counter.build()
            .name("datapool_sum_latency")
            .labelNames(labelNamesExt)
            .help("Self response time (micro sec). Sum for requests by method").register(c);

    static final Counter getNextRetries = Counter.build()
            .name("datapool_get_next_retries")
            .labelNames(labelNames)
            .help("Retries inside get-next-value: row was locked or absent and the cursor was moved (cycling).").register(c);

    static final Counter requestsCount = Counter.build()
            .name("datapool_requests_count")
            .labelNames(labelNamesExt)
            .help("Count read values requests").register(c);

    //Other attributes
    private String host = "undefined";

//    @Value(value = "${server.port:1}")
    private int port = 8080;
    static private long nextCalulationTime = 0;
    static private long calculationDelay = 15000; //15 sec


    public boolean isCalcAVRows = false; //getAvailableRows


    public Exporter(){
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            System.err.println("Failed to get host name");
        }
    }

    public String getMetrics() throws IOException {
        if (nextCalulationTime < System.currentTimeMillis() ) {
            this.setMetrics();
        }
        Writer writer = new StringWriter();
        TextFormat.write004(writer, c.metricFamilySamples() );
        return writer.toString();
    }

    private void  saveMetrics (String env, String pool, int totalCnt, int availCnt, int seqOffset){

        if (totalCnt >= 0) {totalRows.labels(new String [] {host,String.valueOf(port),env.toUpperCase(), pool.toUpperCase()}).set(totalCnt);}
        if (seqOffset >= 0) {currentOffset.labels(new String [] {host,String.valueOf(port),env.toUpperCase(), pool.toUpperCase()}).set(seqOffset);}
        if (availCnt >= 0) {availableRows.labels(new String [] {host,String.valueOf(port),env.toUpperCase(), pool.toUpperCase()}).set(availCnt);}


    }

    public void setMetrics (){

        String sql = "select table_schema, table_name, row_count_estimate as row_count, current_value\n" +
                "        from INFORMATION_SCHEMA.TABLES t\n" +
                "        join  INFORMATION_SCHEMA.SEQUENCES s on t.table_schema = s.sequence_schema and sequence_name = substr('SEQ_'||t.table_name, 0, 25)||'_RID'\n" +
                "        where table_schema not in ('INFORMATION_SCHEMA', 'PUBLIC')";
        try {
            List<String> pools = new ArrayList<>();

            List<Map<String, Object>> rows = jdbcOperations.queryForList(sql);

            for (Map row : rows) {
                saveMetrics (
                        (String) row.get ("table_schema"),
                        (String) row.get ("table_name"),
                        ((Long) row.get ("row_count")).intValue(),getAvailableRows ((String) row.get ("table_schema"),  (String) row.get ("table_name")),
                        ((Long) row.get ("current_value")).intValue());
            }
            nextCalulationTime = System.currentTimeMillis() + calculationDelay;
        } catch (EmptyResultDataAccessException e){
        }
    }
    public void increaseRequests(String env, String pool, String method, String details){  //+1 increment got get requests metric
        if (details == null){
            details = "";
        }
        requestsCount.labels(new String [] {host,String.valueOf(port),env.toUpperCase(), pool.toUpperCase(), method.toUpperCase(), details}).inc();
    }

    public void increaseLatency(String env, String pool, String method, String details, Instant start){  //Save response time
        Instant end = Instant.now();
        if (details == null){
            details = "";
        }
        Duration timeElapsed = Duration.between(start, end);
        latency.labels(new String [] {host,String.valueOf(port),env.toUpperCase(), pool.toUpperCase(), method.toUpperCase(), details}).inc(timeElapsed.toNanos()/1000 );
    }
    private int getAvailableRows (String schema, String pool){
        if (!isCalcAVRows) return -1;
        return ((Long)this.jdbcOperations.queryForObject("SELECT COUNT (locked) FROM " + schema + "." +pool + "  where locked != true", Long.class)).intValue();
    }

    /** Накопленные с запуска запросы, ошибки и суммарное время ответа (микросекунды) одного пула. */
    public static final class Totals {
        public long requests;
        public long errors;
        public long latencyMicros;
        public long retries;     // повторы внутри get-next-value (сдвиги курсора)
        public long loopFails;   // запросы, у которых цикл исчерпал лимит попыток, не найдя данные
    }

    /** Подписи успешных ответов: по ним запрос не считается ошибкой, хотя details непустой. */
    private static final String LOOP_FAIL = "Retry limit exceeded";
    private static final java.util.Set<String> NOT_ERRORS = new java.util.HashSet<>(java.util.Arrays.asList("", "File prepared"));

    /**
     * Накопленные счётчики по пулам (ключ ENV.POOL) для сборщика статистики. Читает коллекторы напрямую, без текстового вывода /metrics.
     * Ошибка — запрос с непустым details (кроме успешных подписей).
     */
    public Map<String, Totals> requestTotals() {
        Map<String, Totals> res = new LinkedHashMap<>();
        for (Collector.MetricFamilySamples family : requestsCount.collect()) {
            for (Collector.MetricFamilySamples.Sample sample : family.samples) {
                Totals t = res.computeIfAbsent(sample.labelValues.get(2) + "." + sample.labelValues.get(3), k -> new Totals());
                long v = (long) sample.value;
                t.requests += v;
                if (!NOT_ERRORS.contains(sample.labelValues.get(5))) t.errors += v;
                if (LOOP_FAIL.equals(sample.labelValues.get(5)) && "GET-NEXT-VALUE".equals(sample.labelValues.get(4))) t.loopFails += v;
            }
        }
        for (Collector.MetricFamilySamples family : getNextRetries.collect()) {
            for (Collector.MetricFamilySamples.Sample sample : family.samples) {
                res.computeIfAbsent(sample.labelValues.get(2) + "." + sample.labelValues.get(3), k -> new Totals()).retries += (long) sample.value;
            }
        }
        for (Collector.MetricFamilySamples family : latency.collect()) {
            for (Collector.MetricFamilySamples.Sample sample : family.samples) {
                Totals t = res.computeIfAbsent(sample.labelValues.get(2) + "." + sample.labelValues.get(3), k -> new Totals());
                t.latencyMicros += (long) sample.value;
            }
        }
        return res;
    }

    /** Считает повторы внутри одного get-next-value (строка занята или отсутствует, курсор сдвигается). */
    public void increaseRetries(String env, String pool, int n) {
        getNextRetries.labels(new String[]{host, String.valueOf(port), env.toUpperCase(), pool.toUpperCase()}).inc(n);
    }

    public void removeMetrics(String env, String pool){
        totalRows.remove(new String [] {host,String.valueOf(port),env.toUpperCase(), pool.toUpperCase() });
        availableRows.remove(new String [] {host,String.valueOf(port),env.toUpperCase(), pool.toUpperCase()});
        currentOffset.remove(new String [] {host,String.valueOf(port),env.toUpperCase(), pool.toUpperCase()});
        getNextRetries.remove(host, String.valueOf(port), env.toUpperCase(), pool.toUpperCase());


    }
    public void incRequestsAndLatency(String env, String pool, String method, String details, Instant start){
        increaseRequests(env, pool, method, details);
        increaseLatency(env, pool, method, details, start);
    }
}
