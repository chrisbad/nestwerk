# --- Build-Stage ---
# Das JAR ist plattformunabhängig: auf der Plattform des Builders bauen (schnell auch für arm64-Images)
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
WORKDIR /build
ARG APP_VERSION=0.0.1-SNAPSHOT

# Zuerst nur Maven-Dateien kopieren, damit Dependencies gecacht werden
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY nestwerk-core/pom.xml nestwerk-core/pom.xml
COPY nestwerk-calendar/pom.xml nestwerk-calendar/pom.xml
COPY nestwerk-notification/pom.xml nestwerk-notification/pom.xml
COPY nestwerk-contact/pom.xml nestwerk-contact/pom.xml
COPY nestwerk-todo/pom.xml nestwerk-todo/pom.xml
COPY nestwerk-household/pom.xml nestwerk-household/pom.xml
COPY nestwerk-kitchen/pom.xml nestwerk-kitchen/pom.xml
COPY nestwerk-attachment/pom.xml nestwerk-attachment/pom.xml
COPY nestwerk-transfer/pom.xml nestwerk-transfer/pom.xml
COPY nestwerk-maintenance/pom.xml nestwerk-maintenance/pom.xml
COPY nestwerk-web/pom.xml nestwerk-web/pom.xml
COPY nestwerk-ai/pom.xml nestwerk-ai/pom.xml
COPY nestwerk-app/pom.xml nestwerk-app/pom.xml
# die eigenen Module existieren erst nach dem Bauen und werden nicht vorab geladen
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline -DexcludeGroupIds=club.badernet

COPY nestwerk-core/src nestwerk-core/src
COPY nestwerk-calendar/src nestwerk-calendar/src
COPY nestwerk-notification/src nestwerk-notification/src
COPY nestwerk-contact/src nestwerk-contact/src
COPY nestwerk-todo/src nestwerk-todo/src
COPY nestwerk-household/src nestwerk-household/src
COPY nestwerk-kitchen/src nestwerk-kitchen/src
COPY nestwerk-attachment/src nestwerk-attachment/src
COPY nestwerk-transfer/src nestwerk-transfer/src
COPY nestwerk-maintenance/src nestwerk-maintenance/src
COPY nestwerk-web/src nestwerk-web/src
COPY nestwerk-ai/src nestwerk-ai/src
COPY nestwerk-app/src nestwerk-app/src
RUN ./mvnw -B -q -DskipTests -Drevision="${APP_VERSION}" package \
    && mv nestwerk-app/target/nestwerk-*.jar app.jar

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
COPY --from=build /build/app.jar app.jar

USER nestwerk
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
