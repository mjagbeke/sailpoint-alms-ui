# Build the fat JAR locally first: mvn package -DskipTests
# Then: docker build -t sailpoint-alms-ui .
# Run:  docker run -p 8080:8080 sailpoint-alms-ui

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/sailpoint-alms-ui-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
