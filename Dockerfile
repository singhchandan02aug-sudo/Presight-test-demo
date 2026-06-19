FROM gradle:9.5-jdk21 AS builder
WORKDIR /workspace
COPY . /workspace
RUN ./gradlew realworld:bootJar --no-daemon --quiet

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /workspace/server/api/build/libs/*.jar /app/realworld.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/realworld.jar"]
