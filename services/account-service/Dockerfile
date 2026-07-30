FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY services/account-service/build/libs/app.jar app.jar
ENTRYPOINT ["java","-XX:+UseSerialGC","-Xms64m","-Xmx192m","-jar","app.jar"]
