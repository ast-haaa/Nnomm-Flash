# Stage 1: Build Spring Boot jar
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app

# Copy gradle wrapper and configurations
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ ./gradle/

# Make gradlew executable
RUN chmod +x gradlew

# Download dependencies (cache layer)
RUN ./gradlew --no-daemon dependencies || true

# Copy source code and resources
COPY src/ ./src/

# Build production Spring Boot jar
RUN ./gradlew --no-daemon bootJar -x test

# Stage 2: Minimal Runtime image
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Create a non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy Spring Boot runnable jar from builder
COPY --from=builder --chown=appuser:appgroup /app/build/libs/*.jar app.jar

USER appuser

EXPOSE 8080 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
