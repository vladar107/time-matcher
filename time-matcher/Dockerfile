# syntax=docker/dockerfile:1
ARG AOT_VARIANT=noaot

# ---- build ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon buildFatJar
RUN cp build/libs/*-all.jar /app.jar

# ---- train (AOT cache; only built when AOT_VARIANT=aot) ----
FROM eclipse-temurin:25-jre AS train
WORKDIR /train
COPY --from=build /app.jar /train/app.jar
COPY --from=build /src/src/main/resources/logback.xml /train/logback.xml
ENV CALENDAR_PROVIDER=google GOOGLE_CLIENT_ID=dummy GOOGLE_CLIENT_SECRET=dummy \
    DB_URL="jdbc:h2:mem:train;DB_CLOSE_DELAY=-1"
RUN timeout --signal=TERM 30 \
    java -Dlogback.configurationFile=/train/logback.xml -XX:AOTCacheOutput=/train/app.aot -jar /train/app.jar || true; \
    test -f /train/app.aot

# ---- base runtime (shared) ----
FROM eclipse-temurin:25-jre AS base
WORKDIR /app
# OpenTelemetry Java agent, pinned. Disabled by default (no OTLP noise); the deploy .env enables it.
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.29.0/opentelemetry-javaagent.jar /opt/otel/opentelemetry-javaagent.jar
RUN useradd -r -u 10001 app && mkdir -p /app/docs && chown -R app:app /app /opt/otel
COPY --chown=app:app --from=build /app.jar /app/app.jar
COPY --chown=app:app --from=build /src/src/main/resources/logback.xml /app/logback.xml
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/opentelemetry-javaagent.jar" \
    OTEL_SDK_DISABLED=true
USER app
EXPOSE 8080

# ---- runtime-noaot (default) ----
FROM base AS runtime-noaot
ENTRYPOINT ["java", "-Dlogback.configurationFile=/app/logback.xml", "-jar", "/app/app.jar"]

# ---- runtime-aot (future scale-to-zero) ----
FROM base AS runtime-aot
COPY --chown=app:app --from=train /train/app.aot /app/app.aot
ENTRYPOINT ["java", "-Dlogback.configurationFile=/app/logback.xml", "-XX:AOTCache=/app/app.aot", "-jar", "/app/app.jar"]

# ---- final (selected by AOT_VARIANT) ----
FROM runtime-${AOT_VARIANT} AS final
