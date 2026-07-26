# syntax=docker/dockerfile:1

# ---- build stage: JDK 21, produce an install image with all jars on classpath ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
# Tests need a bound port and 1 GB of loopback traffic; the image build stays a
# build. `./gradlew test` is the gate, run outside the container.
# Normalise the wrapper script (may carry CRLF when authored on Windows) then build.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && \
    ./gradlew --no-daemon --console=plain installDist

# ---- runtime stage: JRE 21 only ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/install/junction/lib ./lib
COPY junction.yaml ./junction.yaml
# MAIN selects the entry point: the proxy, or the chaos backend fixture.
ENV MAIN=io.junction.Junction \
    JUNCTION_CONFIG=/app/junction.yaml \
    JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp \"/app/lib/*\" $MAIN"]
