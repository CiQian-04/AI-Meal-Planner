# syntax=docker/dockerfile:1
#
# Multi-stage build for the Spring AI backend.
# Stage 1 resolves dependencies, stage 2 ships a slim JRE with only the runtime jar.

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies first so that source-only changes reuse the cached layer.
# Shared GitHub Actions runners occasionally get HTTP 429 from Maven Central,
# so retry a few times instead of failing the whole image build.
COPY pom.xml .
RUN for i in 1 2 3 4 5; do \
      mvn -B -ntp -q dependency:go-offline && break; \
      if [ "$i" -eq 5 ]; then exit 1; fi; \
      echo "Maven dependency resolution failed (attempt $i/5); retrying in 15s..."; \
      sleep 15; \
    done

COPY src ./src
RUN for i in 1 2 3 4 5; do \
      mvn -B -ntp -q clean package -DskipTests && break; \
      if [ "$i" -eq 5 ]; then exit 1; fi; \
      echo "Maven package failed (attempt $i/5); retrying in 15s..."; \
      sleep 15; \
    done

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# Upgrade base packages so CVEs published after the base image build are patched at build time.
RUN apt-get update && apt-get upgrade -y --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

# Run as a non-root user.
RUN groupadd --system appgroup && useradd --system --gid appgroup appuser

COPY --from=build /build/target/*.jar app.jar
RUN chown appuser:appgroup app.jar

USER appuser
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0" \
    SPRING_PROFILES_ACTIVE=prod

# The jre-jammy image has no curl, and `sh` is dash (no /dev/tcp), so the check runs under bash.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
