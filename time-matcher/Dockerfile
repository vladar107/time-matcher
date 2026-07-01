# ---- build ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon buildFatJar
RUN cp build/libs/*-all.jar /app.jar

# ---- train (produce the JDK 25 AOT cache from an H2/in-memory boot) ----
# Must use the same JRE as the runtime stage so lib/modules matches and the cache loads.
FROM eclipse-temurin:25-jre AS train
WORKDIR /train
COPY --from=build /app.jar /train/app.jar
COPY --from=build /src/src/main/resources/logback.xml /train/logback.xml
# Boot in google mode with dummy creds + H2 in-memory (no network at startup, no external DB),
# capture the AOT cache, then SIGTERM after startup so the JVM exits and writes app.aot.
ENV CALENDAR_PROVIDER=google \
    GOOGLE_CLIENT_ID=dummy \
    GOOGLE_CLIENT_SECRET=dummy \
    DB_URL="jdbc:h2:mem:train;DB_CLOSE_DELAY=-1"
RUN timeout --signal=TERM 30 \
    java -Dlogback.configurationFile=/train/logback.xml -XX:AOTCacheOutput=/train/app.aot -jar /train/app.jar || true; \
    test -f /train/app.aot

# ---- runtime ----
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
RUN useradd -r -u 10001 app && \
    mkdir -p /app/docs && \
    chown -R app:app /app
COPY --chown=app:app --from=build /app.jar /app/app.jar
COPY --chown=app:app --from=train /train/app.aot /app/app.aot
COPY --chown=app:app --from=build /src/src/main/resources/logback.xml /app/logback.xml
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-Dlogback.configurationFile=/app/logback.xml", "-XX:AOTCache=/app/app.aot", "-jar", "/app/app.jar"]
