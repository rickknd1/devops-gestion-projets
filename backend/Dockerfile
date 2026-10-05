# Le jar est construit par le pipeline Jenkins (stage Package) :
# l'image ne contient que le JRE et l'application.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
