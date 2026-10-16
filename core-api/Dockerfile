# syntax=docker/dockerfile:1

# ===== Stage 1 - build =====
# Dependencies resolve in their own layer keyed on pom.xml. Tests are not run here: CI runs them
# against Testcontainers, and a build stage needing its own Docker daemon is the wrong place.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests package

# Split the fat jar into layers that change at different rates (deps rarely, classes every commit).
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# ===== Stage 2 - runtime =====
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Nothing here needs root.
RUN adduser --system --disabled-password --uid 10001 filmfuse
USER filmfuse

COPY --from=build --chown=filmfuse:filmfuse /build/extracted/dependencies/ ./
COPY --from=build --chown=filmfuse:filmfuse /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=filmfuse:filmfuse /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=filmfuse:filmfuse /build/extracted/application/ ./

# Deployed image => prod profile, which takes every value from the environment and refuses to
# start without them.
ENV SPRING_PROFILES_ACTIVE=prod

# Size the heap from the container limit, not the host's memory. JAVA_OPTS (not JAVA_TOOL_OPTIONS)
# so an unrelated JAVA_TOOL_OPTIONS can't silently replace it.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Documentation only - Railway injects PORT and application-prod binds it.
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- "http://127.0.0.1:${PORT:-8080}/actuator/health" > /dev/null || exit 1

# exec => the JVM is PID 1 and gets SIGTERM directly, so graceful shutdown drains requests.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
