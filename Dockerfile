# Band’IAkam — image de déploiement (Render, ou tout hébergeur Docker).
# 1. Construction du jar avec Maven.
FROM maven:3.9-eclipse-temurin-17 AS construction
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package

# 2. Exécution, avec un simple JRE.
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=construction /app/target/*.jar app.jar
# La mémoire suit la taille de la machine (512 Mo sur l'offre gratuite de Render).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC" \
    TZ=Indian/Antananarivo
# Render fournit le port dans PORT (lu par server.port=${PORT:8090}).
EXPOSE 8090
CMD ["java", "-jar", "app.jar"]
