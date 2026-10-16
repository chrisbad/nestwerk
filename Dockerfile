# --- Build-Stage ---
# Das JAR ist plattformunabhängig: auf der Plattform des Builders bauen (schnell auch für arm64-Images)
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
WORKDIR /build
ARG APP_VERSION=0.0.1-SNAPSHOT

# Zuerst nur Maven-Dateien kopieren, damit Dependencies gecacht werden
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q -DskipTests -Drevision="${APP_VERSION}" package \
    && mv target/nestwerk-*.jar target/app.jar

# --- Runtime-Stage ---
FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.source="https://github.com/chrisbad/nestwerk" \
      org.opencontainers.image.title="Nestwerk" \
      org.opencontainers.image.description="Familien-App: Kalender, ToDos, Haushaltsplan, Kontakte"
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 nestwerk \
    && mkdir -p /data/attachments /data/backups /data/external /data/updater \
    && chown -R nestwerk /data

WORKDIR /app
COPY --from=build /build/target/app.jar app.jar

USER nestwerk
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
