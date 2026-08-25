# Build context is the repo root: docker build -f rule-service/Dockerfile .
# A whole-repo context is required because this is a multi-module Maven reactor
# (ADR 0003) — building rule-service needs common's sources available to the
# reactor via `mvn -pl rule-service -am`.

FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

# POMs only, first: this layer is cached across source-only edits.
COPY pom.xml .
COPY config config
COPY common/pom.xml common/pom.xml
COPY rule-service/pom.xml rule-service/pom.xml
COPY deployment-service/pom.xml deployment-service/pom.xml
COPY simulators/pom.xml simulators/pom.xml
COPY simulators/orders-service/pom.xml simulators/orders-service/pom.xml
COPY simulators/payments-service/pom.xml simulators/payments-service/pom.xml
RUN mvn -B -pl rule-service -am dependency:go-offline

COPY common/src common/src
COPY rule-service/src rule-service/src
RUN mvn -B -pl rule-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/rule-service/target/rule-service-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
