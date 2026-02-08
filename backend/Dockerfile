# =========================
# 1) Build stage
# =========================
FROM eclipse-temurin:17-jdk AS build

WORKDIR /app

COPY .mvn .mvn
# Copy wrapper + pom into /app
COPY mvnw pom.xml ./
# Copy the mvn/ directory so /app/mvn/settings.xml exists
COPY mvn mvn

RUN chmod +x mvnw

# 1a. Download dependencies (optional but good for caching)
RUN ./mvnw -B -q dependency:go-offline

# 1b. Copy source and build the JAR
COPY src src

RUN ./mvnw -B -q package -DskipTests

# =========================
# 2) Runtime stage
# =========================
FROM eclipse-temurin:17-jre

WORKDIR /app

# This now exists because of the package step above
COPY --from=build /app/target/backend-0.0.1-SNAPSHOT.jar app.jar

ENV JAVA_OPTS=""

EXPOSE 8080 8081

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]