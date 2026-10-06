# ---------- Etapa 1: build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cachea dependencias: solo se invalida si cambia el pom
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Los tests usan Testcontainers (requieren Docker); se corren en CI, no en el build de la imagen
RUN mvn -B -q package -DskipTests \
 && cp target/*.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------- Etapa 2: runtime ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache tzdata \
 && addgroup -S sentria && adduser -S sentria -G sentria

ENV TZ=America/Argentina/Cordoba \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=America/Argentina/Cordoba"

# Capas ordenadas de menos a más cambiantes
COPY --from=build /app/extracted/dependencies/ ./
COPY --from=build /app/extracted/spring-boot-loader/ ./
COPY --from=build /app/extracted/snapshot-dependencies/ ./
COPY --from=build /app/extracted/application/ ./

USER sentria
EXPOSE 8080

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
