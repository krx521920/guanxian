FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -ntp clean package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar app.jar
RUN addgroup -S guanxian && adduser -S -G guanxian -h /app guanxian \
    && chown -R guanxian:guanxian /app
USER guanxian
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
