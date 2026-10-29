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

Open http://localhost:8080/pools.html: table of all pools (diverged first), repair button per pool and bulk repair. REST: `GET /api/v1/pools`, `POST /api/v1/pools/repair`, `POST /api/v1/pools/repair-batch`. See [docs](src/main/resources/static/index.html#Pools).

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