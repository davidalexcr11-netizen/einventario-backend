# Usamos una máquina con Java 17 y Maven
FROM maven:3.8.5-openjdk-17 AS build
COPY . .
# Compilamos el proyecto
RUN mvn clean package -DskipTests

# Preparamos el servidor final para correr la app
FROM openjdk:17.0.1-jdk-slim
COPY --from=build /target/backend-1.0-SNAPSHOT-jar-with-dependencies.jar app.jar
# Exponemos el puerto y arrancamos el servidor
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]