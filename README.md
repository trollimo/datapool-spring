<h1 align="center">datapool-spring</h1>

<p align="center">
  Сервис тестовых данных для нагрузочных тестов: пулы строк, которые выдаются по кругу, блокируются и ищутся по ключу.<br>
  REST · страницы «Пулы» и «Статистика» · MCP для ИИ-агентов · OpenAPI
</p>

<p align="center">
  <img alt="version" src="https://img.shields.io/badge/version-3.1.1-blue">
  <img alt="java" src="https://img.shields.io/badge/java-8%2B-orange">
  <img alt="spring boot" src="https://img.shields.io/badge/spring%20boot-2.1-6db33f">
  <img alt="db" src="https://img.shields.io/badge/db-H2-lightgrey">
  <img alt="tests" src="https://img.shields.io/badge/tests-118%20passed-brightgreen">
  <img alt="load" src="https://img.shields.io/badge/load-4500%20RPS%20%7C%20p50%201%20ms-brightgreen">
</p>

---

## Возможности

- **Выдача по кругу** (`get-next-value`), с блокировкой строки или без; **поиск по ключу**; вставка; загрузка и выгрузка CSV.
- **Надёжность под нагрузкой:** без дублей блокировок и бесконечных циклов, проверено до **4500 RPS** (RTT p50 1 мс, p99 35 мс) при ~30 % занятых строк.
- 🛠 **Страница «Пулы»:** состояние кэша и БД, починка счётчика, скачать и удалить пул (иконки в строке, подтверждение удаления).
- 📈 **Страница «Статистика»:** RPS, RTT, ошибки, остаток пула и зацикливания за час, сводная таблица.
- 🤖 **MCP** (`/mcp`): обслуживание пулов из ИИ-агента, с лимитами и инструкцией «как не испортить идущий тест».
- 📜 **OpenAPI 3.0** всех ручек.

| | |
|---|---|
| [**Новые возможности со скриншотами**](docs/FEATURES.md) | страницы, MCP, надёжность, структура кода |
| [**Результаты нагрузочного тестирования**](docs/load-test-3.1.md) | профиль, RPS, RTT, графики, сравнение версий |
| [Список изменений](changelog.md) | по версиям |
| [Спецификация API](api-specification/openapi.yaml) | OpenAPI 3.0.3 |
| [Gatling-тесты](tests/load/README.md) | как запускать и читать вердикт |

![Статистика](docs/images/statistics-top.jpg)

## Как это работает

![](src/main/resources/static/schema.jpg)

Пул — таблица H2 `ENV.POOL` (`rid`, `text` JSON, `searchkey`, `locked`). Курсор чтения — sequence в БД, блокировки дополнительно кэшируются в памяти приложения.

## Запуск

Нужна только Java 8+. Jar самодостаточный, Maven и доступ в сеть не нужны:

```bash
cd ./bin/
java -jar datapool-service-3.1.1.jar
```

Страницы: `http://localhost:8080/` (документация), `/pools.html`, `/statistics.html`.

## Быстрый пример

```bash
# скачать демо-пул и загрузить в сервис
curl https://raw.githubusercontent.com/Roman-Kislyy/datapool-spring/main/src/test/test.csv --output test.csv -k
curl -F 'file=@test.csv' "http://localhost:8080/api/v1/upload-csv-as-json?env=load&pool=demo_clients&override=true&delimiter=,"

# взять следующее значение
curl -s "http://localhost:8080/api/v1/get-next-value?pool=demo_clients&locked=false"
```

![](src/main/resources/static/demo_use.jpg)

## Настройка

Файл [bin/application.properties](bin/application.properties). Из нового:

| Параметр | По умолчанию | Назначение |
|---|---|---|
| `datapool.getNext.maxRetries` | 1000 | лимит попыток `get-next-value` (защита от зацикливания) |
| `datapool.statistics.interval-seconds` | 15 | шаг сбора статистики |
| `datapool.statistics.window-minutes` | 60 | сколько истории хранить в памяти |
| `datapool.mcp.max-calls-per-minute` | 30 | лимит вызовов тул MCP |
| `datapool.mcp.download.max-rows` | 5000 | лимит выгрузки через MCP |
| `datapool.mcp.upload.max-chars` | 1000000 | лимит загрузки через MCP |

## Документация

- [Документация сервиса (htmlpreview)](https://htmlpreview.github.io/?https://github.com/Roman-Kislyy/datapool-spring/blob/master/src/main/resources/static/index.html)
- Локально: `http://localhost:8080/` после запуска.
- [Исходник документации](src/main/resources/static/index.html)

## Структура кода

```
todo/rest, mcp   контроллеры: параметры, вызов сервиса, метрики запроса
service          сценарии (PoolService, CsvExportService, ...), кэш блокировок, статистика
repository       PoolRepository: весь SQL и DDL пулов
prometheus, db   экспортёр метрик, JDBC
```

Структура БД (таблица, индексы `_rid`/`_lc`/`_sk`, sequence `SEQ_<POOL>_RID`/`_MAX`) без миграции не меняется: `DbStructureTest` сверяет её с эталоном.

## Сборка

```bash
mvn clean verify     # тесты + jar в bin/
```

Нагрузочные тесты (Gatling, JDK 17): [tests/load](tests/load/README.md).

## Полезные ссылки

[H2: tutorial](http://www.h2database.com/html/tutorial.html#using_server) ·
[H2: create](https://www.tutorialspoint.com/h2_database/h2_database_create.htm) ·
[Spring REST](https://spring.io/guides/gs/rest-service/) ·
[REST API with Spring](https://alexkosarev.name/2019/03/08/rest-api-with-spring/) ·
[Spring Boot + H2](https://www.baeldung.com/spring-boot-h2-database)
