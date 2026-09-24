# The build stage runs on the builder's own architecture ($BUILDPLATFORM): a jar is
# architecture-independent, so building it under QEMU for arm64 would only be slower.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /build/target/wallet-ledger-0.1.0-SNAPSHOT.jar app.jar
USER app
EXPOSE 8091
# MaxRAMPercentage instead of a fixed -Xmx: the heap follows the container's memory limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
