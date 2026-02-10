# ── Stage 1: Build ────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Cache dependencies first (layer caching optimisation)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# ── Stage 2: Runtime ─────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Install curl for HEALTHCHECK and create non-root user
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system appuser && useradd --system --gid appuser appuser

# Copy the fat JAR from the build stage
COPY --from=build /app/target/pdf-inverter-backend-1.0.0.jar app.jar

# Temp directory for PDF processing
RUN mkdir -p /app/tmp && chown -R appuser:appuser /app

USER appuser

# Port configuration (default 9090, overridable via env)
ENV PORT=9090
EXPOSE ${PORT}

# JVM memory flags
ENV JAVA_OPTS="-Xmx512m -Xms256m"

# Health check against the health endpoint
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -f http://localhost:${PORT}/api/pdf/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Djava.io.tmpdir=/app/tmp -Dserver.port=${PORT} -jar app.jar"]
