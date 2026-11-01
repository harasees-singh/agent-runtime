FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /work
COPY . .
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /work/runtime-app/target/runtime-app-0.1.0-SNAPSHOT.jar /app/runtime.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/runtime.jar"]
