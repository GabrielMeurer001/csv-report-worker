FROM node:24-alpine AS frontend
WORKDIR /build
COPY frontend/package*.json ./
RUN npm ci
COPY frontend ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
COPY --from=frontend /build/dist ./src/main/resources/static
COPY examples/sales.csv ./src/main/resources/static/examples/sales.csv
RUN mvn -B -ntp package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/target/csv-report-worker-0.1.0.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
