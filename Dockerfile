# syntax=docker/dockerfile:1.7
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B --no-transfer-progress dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 roster \
    && useradd --uid 10001 --gid roster --no-create-home --home-dir /app --shell /usr/sbin/nologin roster \
    && mkdir -p /data/snapshots \
    && chown -R roster:roster /data
COPY --from=build --chown=roster:roster /build/target/*.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=8s --start-period=90s --retries=12 \
    CMD curl --fail --silent --max-time 6 http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
