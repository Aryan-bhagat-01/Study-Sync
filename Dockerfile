FROM maven:3.9.4-eclipse-temurin-17

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

CMD ["mvn", "exec:java", "-Dexec.mainClass=StudySyncBot"]