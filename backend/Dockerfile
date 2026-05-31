# ── Build stage ────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copiar wrapper y descriptor de dependencias primero
# Docker cachea esta capa hasta que pom.xml cambie
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Asegurar permisos de ejecución del wrapper (necesario en Linux)
# y descargar dependencias con cache de Maven entre builds
# Cache separado por arquitectura — evita conflictos en builds multi-arch
RUN --mount=type=cache,target=/root/.m2,id=maven-$TARGETARCH \
    chmod +x mvnw && \
    ./mvnw -q -B dependency:go-offline

# Copiar fuentes en capa separada — invalida cache solo si el código cambia
COPY src/ src/

# TODO: cambiar a -DskipTests cuando los tests estén estables
# Por ahora se salta compilación y ejecución de tests (-Dmaven.test.skip=true)
RUN --mount=type=cache,target=/root/.m2,id=maven-$TARGETARCH \
    ./mvnw -q -B package -Dmaven.test.skip=true

# ── Runtime stage ──────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# Usuario no root para reducir superficie de ataque
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

WORKDIR /app

# Copiar únicamente el JAR producido por el build stage
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

# Health check vía Actuator — wget nativo en Alpine
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# Flags JVM optimizados para contenedores
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
