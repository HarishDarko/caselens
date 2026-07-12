FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY backend/pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY backend/src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN rm -f /usr/bin/pebble \
    && useradd --system --uid 10001 --create-home caselens
COPY --from=build /workspace/target/caselens-0.0.1-SNAPSHOT.jar /app/caselens.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/caselens.jar"]
