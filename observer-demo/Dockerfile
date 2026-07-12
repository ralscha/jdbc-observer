FROM maven:3.9.16-eclipse-temurin-25 AS build

WORKDIR /workspace
COPY pom.xml ./
COPY observer-core/pom.xml observer-core/pom.xml
COPY observer-core/src observer-core/src
COPY observer-agent/pom.xml observer-agent/pom.xml
COPY observer-agent/src observer-agent/src
COPY observer-ui/pom.xml observer-ui/pom.xml
COPY observer-demo/pom.xml observer-demo/pom.xml
COPY observer-demo/src observer-demo/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl observer-agent,observer-demo -am -DskipTests package

FROM eclipse-temurin:25-jre-alpine

WORKDIR /app
COPY --from=build /workspace/observer-agent/target/observer-agent.jar observer-agent.jar
COPY --from=build /workspace/observer-demo/target/observer-demo.jar app.jar

RUN addgroup -S observer && adduser -S observer -G observer
USER observer

EXPOSE 8080

ENTRYPOINT ["java", "-javaagent:/app/observer-agent.jar=mode=client,host=host.docker.internal,port=4561", "-jar", "/app/app.jar"]
