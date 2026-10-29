package load.datapool.todo.rest;

import load.datapool.service.CsvExportService;
import load.datapool.service.PoolException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api/v1")
public class CsvDownloadController {

    private final CsvExportService export;

    @Autowired
    public CsvDownloadController(CsvExportService export) {
        this.export = export;
    }

    @GetMapping("/download/csv")
    public ResponseEntity<byte[]> downloadCsv(@RequestParam(value = "env", defaultValue = "load") String env,
                                              @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                              @RequestParam(value = "delimiter", defaultValue = ",") String delim,
                                              @RequestParam(value = "all_columns", defaultValue = "false") boolean allColumns,
                                              @RequestParam(value = "batch_size", defaultValue = "0") int batchSize,
                                              @RequestParam(value = "offset", defaultValue = "0") int offset) {
        try {
            byte[] csvBytes = export.export(env, pool, delim, allColumns, batchSize, offset);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + env + "." + pool + ".csv");
            headers.add(HttpHeaders.CONTENT_TYPE, "text/csv");
            headers.add(HttpHeaders.CONTENT_LENGTH, String.valueOf(csvBytes.length));
            return new ResponseEntity<>(csvBytes, headers, HttpStatus.OK);
        } catch (PoolException e) {
            return ResponseEntity.status(e.getStatus()).body(e.getMessage().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
