FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml mvn -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml mvn -B -q package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 depradar
COPY --from=build /build/target/depradar-1.0.0.jar app.jar
USER depradar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
