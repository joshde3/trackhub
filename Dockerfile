# Two stages: build with Maven, run on a small JRE image. The final image holds no source,
# no Maven, and no secrets; configuration comes from environment variables at runtime.

FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
# POMs first, so dependency downloads are cached until a POM changes.
COPY pom.xml .
COPY trackhub-domain/pom.xml trackhub-domain/
COPY trackhub-carriers/pom.xml trackhub-carriers/
COPY trackhub-app/pom.xml trackhub-app/
# Best effort: modules depending on each other can't resolve yet, and the package step fetches the rest.
RUN mvn -B -ntp -q dependency:go-offline -DexcludeReactor=true || true
COPY . .
# Tests run in CI; the image build only packages.
RUN mvn -B -ntp -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S trackhub && adduser -S trackhub -G trackhub
USER trackhub
WORKDIR /app
COPY --from=build /src/trackhub-app/target/trackhub-app-*.jar app.jar
# Sized for a small container (ADR 0012 tunes this with measurements in Phase 4): heap as a
# share of the container limit, the serial collector (least overhead on 1 CPU), and exit on OOM
# so the platform restarts us instead of limping on.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
