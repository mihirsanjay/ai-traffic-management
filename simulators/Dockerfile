# Shared by both simulators, since they are structurally identical today.
# Build context is the repo root, e.g.:
#   docker build -f simulators/Dockerfile --build-arg MODULE=orders-service --build-arg PORT=9001 -t atm-orders-service .
#   docker build -f simulators/Dockerfile --build-arg MODULE=payments-service --build-arg PORT=9002 -t atm-payments-service .
# If the two simulators' dependencies ever diverge, split this into two files
# rather than adding branching here.

FROM maven:3.9-eclipse-temurin-21-alpine AS build
ARG MODULE
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
RUN mvn -B -pl simulators/${MODULE} -am dependency:go-offline

COPY common/src common/src
COPY simulators/${MODULE}/src simulators/${MODULE}/src
RUN mvn -B -pl simulators/${MODULE} -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
ARG MODULE
ARG PORT
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/simulators/${MODULE}/target/${MODULE}-*.jar app.jar
USER app
EXPOSE ${PORT}
ENTRYPOINT ["java", "-jar", "app.jar"]
