FROM azul/zulu-openjdk-alpine:21-latest

WORKDIR /app

COPY build/libs/popnup-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]