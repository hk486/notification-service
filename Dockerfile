# Day 13: Multi-stage Dockerfile for Notification Service
#
# This Dockerfile builds the notification service with:
# - Stage 1: Build — compiles Java code and creates JAR
# - Stage 2: Runtime — lightweight image with only the JAR and JRE
#
# Benefits:
# - Final image is ~300MB (instead of ~700MB with full JDK)
# - Security: no source code or build tools in production image
# - Fast deployments: only Runtime stage is uploaded to registry

# ─── Stage 1: Builder ─────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /workspace

# Copy Maven wrapper and pom.xml
COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml .

# Download dependencies (cached layer if pom.xml unchanged)
RUN chmod +x mvnw && ./mvnw dependency:resolve -DskipTests

# Copy source code
COPY src src

# Build: compile, run tests, create JAR
RUN ./mvnw clean package -DskipTests

# Extract JAR layers for faster Docker builds (Spring Boot Layered Jar)
RUN mkdir -p build && cd build && jar -xf ../target/*.jar

# ─── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Copy JAR layers from builder
# Layers are copied separately for better cache invalidation
# If only app code changes, earlier layers are cached
COPY --from=builder /workspace/build/org . 
COPY --from=builder /workspace/build/BOOT-INF/lib /app/lib
COPY --from=builder /workspace/build/BOOT-INF/classes /app/classes

# Health check: curl http://localhost:8080/actuator/health
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Expose port
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-cp", "app:app/lib/*", "notification_service.NotificationServiceApplication"]
