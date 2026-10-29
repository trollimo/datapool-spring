package load.datapool.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import load.datapool.db.FullRow;
import load.datapool.prometheus.Exporter;
import load.datapool.repository.PoolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Выгрузка пула в CSV: первая строка — поля JSON, файл можно загрузить обратно (upload-csv-as-json) с тем же разделителем. */
@Service
public class CsvExportService {

    private final Logger logger = LoggerFactory.getLogger(CsvExportService.class);
    private final PoolRepository repository;
    private final Exporter exp;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value(value = "${db.download.batch-rows:10000}")
    private int maxSelectBatch;

    @Value(value = "${db.download.max-rows:500000}")
    private int maxSelectRows;

    @Autowired
    public CsvExportService(PoolRepository repository, Exporter exp) {
        this.repository = repository;
        this.exp = exp;
    }

    public byte[] export(String env, String pool, String delim, boolean allColumns, int batchSize, int offset) {
        PoolNames.require(env, pool);
        Instant start = Instant.now();
        // Метод сильно просаживает производительность поэтому выставляем лимиты
        int localBatchSize = maxSelectBatch;
        int localOffset = 0;
        int exportedRowsCount = 0;

        if (batchSize > 0 && batchSize < maxSelectBatch) {
            localBatchSize = batchSize;
        }
        if (offset > 0) {
            localOffset = offset;
        }

        Set<String> headersSet = new LinkedHashSet<>();
        List<List<String>> rows = new ArrayList<>();

        while (true) {
            List<FullRow> dataList = repository.exportBatch(env, pool, allColumns, localOffset, localBatchSize);
            if (dataList.isEmpty()) {
                break; // Выход из цикла, если нет больше данных
            }

            for (FullRow data : dataList) {
                List<String> row = new ArrayList<>();
                // Сначала технические поля, если запрашивались
                if (allColumns) {
                    headersSet.add("rid");
                    headersSet.add("searchkey");
                    headersSet.add("locked");
                    row.add(String.valueOf(data.getRid()));
                    row.add(data.getSearchkey());
                    row.add(String.valueOf(data.isLocked()));
                }
                // Преобразуем text в json
                try {
                    JsonNode jsonNode = objectMapper.readTree(data.getText());
                    if (!jsonNode.isObject()) {
                        // Проверка типа корневого узла, чтобы дальше не было ошибок
                        throw new JsonProcessingException("JSON должен быть объектом или массивом") {};
                    }
                    jsonNode.fieldNames().forEachRemaining(fieldName -> {
                        headersSet.add(fieldName);
                        row.add(jsonNode.get(fieldName).asText());
                    });
                } catch (IOException e) {
                    // Если в поле text простой текст
                    headersSet.add("raw_text");
                    row.add(data.getText());
                    exp.incRequestsAndLatency(env, pool, "download-csv", "json parsing failed", start);
                }
                rows.add(row);
            }
            exportedRowsCount += dataList.size();
            logger.info("Total exported rows: {}. Offset {}, batch {}.", exportedRowsCount, localOffset, localBatchSize);
            localOffset += localBatchSize;
            if (exportedRowsCount >= maxSelectRows) {
                break; // Выход из цикла, достигли лимита выгрузки
            }
        }

        List<String> headersList = new ArrayList<>(headersSet);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            if (exportedRowsCount <= 0) {
                writer.println("No data found!");
            } else {
                writer.println(String.join(delim, headersList));
                for (List<String> row : rows) {
                    // Дополнение строки пустыми значениями, если нужно
                    while (row.size() < headersList.size()) {
                        row.add("");
                    }
                    writer.println(String.join(delim, row));
                }
            }
            writer.flush();
            exp.incRequestsAndLatency(env, pool, "download-csv", "File prepared", start);
            return out.toByteArray();
        } catch (IOException e) {
            exp.incRequestsAndLatency(env, pool, "download-csv", "Some server error", start);
            throw new PoolException(500, "Some server error", "Some server error");
        }
    }
}
