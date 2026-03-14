# ── Stage 1: Compilación
FROM gradle:8.14-jdk21-alpine AS builder
WORKDIR /app

COPY build.gradle settings.gradle ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon -q || true

COPY src ./src
RUN gradle bootJar --no-daemon -q

# ── Stage 2: Imagen de ejecución (solo JRE)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Usuario no-root
RUN addgroup -S sesgroup && adduser -S sesuser -G sesgroup

COPY --from=builder /app/build/libs/*.jar app.jar

USER sesuser

# Puerto del emulador
EXPOSE 8045

# Health check
HEALTHCHECK --interval=20s --timeout=10s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8045/ --post-data "Action=GetSendQuota" 2>/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]

