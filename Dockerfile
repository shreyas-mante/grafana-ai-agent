# syntax=docker/dockerfile:1
# Multi-stage build: only the compiled jar and the knowledge seed reach the final image.
# No source code, build tools, or secrets are included, and the app runs as a non-root user.

FROM node:22-alpine AS ui
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY sdk/pom.xml sdk/
COPY backend/pom.xml backend/
COPY demo-app/pom.xml demo-app/
RUN mvn -q -B -pl backend -am dependency:go-offline -DskipTests || true
COPY sdk/ sdk/
COPY backend/ backend/
COPY --from=ui /src/backend/src/main/resources/static/ backend/src/main/resources/static/
RUN mvn -q -B -pl backend -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app -H -s /sbin/nologin app \
    && mkdir -p /app/data && chown app:app /app/data
WORKDIR /app
COPY --from=build --chown=root:root /src/backend/target/pulseagent.jar /app/pulseagent.jar
COPY --chown=root:root knowledge/ /app/knowledge/
ENV APP_KNOWLEDGE_DIR=/app/knowledge \
    APP_DATA_DIR=/app/data \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD wget -qO- http://127.0.0.1:8080/api/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/pulseagent.jar"]
