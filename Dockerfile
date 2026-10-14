# --- Build-Stage ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# Zuerst nur Maven-Dateien kopieren, damit Dependencies gecacht werden
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q -DskipTests package \
    && mv target/nestwerk-*.jar target/app.jar

# --- Runtime-Stage ---
FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 nestwerk \
    && mkdir -p /data/attachments \
    && chown -R nestwerk /data

WORKDIR /app
COPY --from=build /build/target/app.jar app.jar

USER nestwerk
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
