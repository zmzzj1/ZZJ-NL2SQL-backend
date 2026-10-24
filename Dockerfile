FROM maven:3.9.11-eclipse-temurin-21 AS build
ARG MODULE=zzj-service
WORKDIR /workspace

COPY pom.xml ./
COPY zzj-common/pom.xml zzj-common/pom.xml
COPY zzj-sandbox/pom.xml zzj-sandbox/pom.xml
COPY zzj-service/pom.xml zzj-service/pom.xml
COPY zzj-gateway/pom.xml zzj-gateway/pom.xml
COPY zzj-admin/pom.xml zzj-admin/pom.xml
RUN mvn -B -ntp -pl "${MODULE}" -am dependency:go-offline

COPY . .
RUN mvn -B -ntp -pl "${MODULE}" -am package -DskipTests

FROM eclipse-temurin:21-jre
ARG MODULE=zzj-service

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl nodejs docker.io \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build "/workspace/${MODULE}/target/${MODULE}.jar" /app/app.jar
COPY --from=build /workspace/tools/mcp-echo-server.mjs /app/tools/mcp-echo-server.mjs

EXPOSE 8080 8081 8083 20880
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
