# JDBC Observer Spring Data JPA demo

This demo runs a Java 25 Spring Boot application against PostgreSQL 18 Alpine. It seeds eight authors with three books each and exposes equivalent inefficient and optimized JPA reads.

Start the observer UI in listener mode from the repository root:

```shell
java -jar observer-ui/target/observer-ui.jar --listen --port=4561
```

Start PostgreSQL and the instrumented application:

```shell
docker compose -f observer-demo/compose.yaml up --build
```

Trigger the intentional N+1 query:

```shell
curl http://localhost:8080/demo/n-plus-one
```

Trigger the optimized `@EntityGraph` query:

```shell
curl http://localhost:8080/demo/fixed
```

The first endpoint performs one author query followed by one lazy book query per author. The second loads the same graph with one SQL statement. The observer UI attributes both to their Spring service call sites and marks the repeated lazy query as an N+1 pattern.
