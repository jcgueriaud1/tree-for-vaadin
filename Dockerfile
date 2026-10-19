# Builds the demo's production bundle and runs it on a small JRE image, sized for
# Render's free instance (512 MB RAM, 0.1 CPU). See render.yaml.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY addon addon
COPY demo demo
# The demo and the add-on module it depends on; -DskipTests also skips the add-on's npm test tooling
RUN mvn -B -DskipTests -pl demo -am package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system app
USER app
COPY --from=build /app/demo/target/tree-for-vaadin-demo.jar app.jar
# Leave room under 512 MB for metaspace, threads and buffers; the serial GC has the
# smallest footprint. With 0.1 CPU, skipping the C2 compiler shortens startup.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
