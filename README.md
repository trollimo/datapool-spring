# datapool-spring
Service for creating and utilizing test data pools via REST API.

# How it's work

![](src/main/resources/static/schema.jpg)

# Start service

`cd ./bin/`

`java -jar datapool-service-<version>.jar`

# Use it

- Download demo data pool 

`curl https://raw.githubusercontent.com/Roman-Kislyy/datapool-spring/main/src/test/test.csv --output test.csv -k` 

- Upload file to local datapool service 

`curl -F 'file=@test.csv' "http://localhost:8080/api/v1/upload-csv-as-json?env=load&pool=demo_clients&override=true&delimiter=,"`

- Get value from datapool service `curl "http://localhost:8080/api/v1/get-next-value?pool=demo_clients&locked=false" -s`

![](src/main/resources/static/demo_use.jpg)

# Configure

[bin/application.properties](bin/application.properties)

# Documentation

- [Docs htmlpreview](https://htmlpreview.github.io/?https://github.com/Roman-Kislyy/datapool-spring/blob/master/src/main/resources/static/index.html)
- [Local](http://localhost:8080/) You can open web documentaion everytime. Simple open http://localhost:8080/ where datapool has been started.
- [Or open resourse](src/main/resources/static/index.html)

# Pools status

Open http://localhost:8080/pools.html: table of all pools (diverged first), icon buttons per pool (reset counter, download CSV, delete with confirmation) and bulk reset of counters. REST: `GET /api/v1/pools`, `POST /api/v1/pools/repair`, `POST /api/v1/pools/repair-batch`. See [docs](src/main/resources/static/index.html#Pools).

# Statistics page

Open http://localhost:8080/statistics.html: rps (with a total line), response time, errors, remaining share of the pool, frequency of cycling, summary table. Data: `GET /api/v1/statistics` (last hour, sampled every 15 s in memory).

# Code structure

    todo/rest, mcp  controllers: parameters in, PoolService/PoolDiagnosticsService out, request metrics
    service         use cases (PoolService, CsvExportService, PoolDiagnosticsService), lock cache (BaseLockerService, BaseLocker), SequenceService
    repository      PoolRepository: all SQL and DDL of pools
    prometheus, db  metrics exporter, JDBC template

The DB structure (table `ENV.POOL`: rid, text, searchkey, locked; indexes `_rid`, `_lc`, `_sk`; sequences `SEQ_<POOL>_RID`, `_MAX`) must not change without a migration; `DbStructureTest` compares it with the golden DDL.

# API specification and MCP

- OpenAPI 3.0: [api-specification/openapi.yaml](api-specification/openapi.yaml)
- MCP for AI agents: `POST http://localhost:8080/mcp` (Streamable HTTP, JSON-RPC 2.0). Add it to an agent as an HTTP MCP server; call the `about` tool first. Tools: about, list_pools, counters_stats, repair_pools, reset_sequence, unlock, put_value, upload_csv, download_pool_csv, drop_pool. Limits: `datapool.mcp.max-calls-per-minute` (30), `datapool.mcp.download.max-rows` (5000), `datapool.mcp.upload.max-chars` (1000000). `get-next-value` and `search-by-key` are not available through MCP.

# Load tests

Gatling regression tests for rid divergence, duplicate locks and looping: [tests/load](tests/load/README.md).

# Build with maven

`mvn clean verify`

# Useful links H2 DB

http://www.h2database.com/html/tutorial.html#using_server

https://www.tutorialspoint.com/h2_database/h2_database_create.htm

# Useful links Spring REST

https://spring.io/guides/gs/rest-service/

https://alexkosarev.name/2019/03/08/rest-api-with-spring/

https://www.baeldung.com/spring-boot-h2-database