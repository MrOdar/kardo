# ============ Stage 1: build ============
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Capa de dependencias: se reutiliza mientras el pom no cambie.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests clean package

# ============ Stage 2: runtime ============
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Instalar fuentes TrueType para soporte de renderizado gráfico de BufferedImage
RUN apk add --no-cache fontconfig ttf-dejavu

# Usuario sin privilegios
RUN addgroup -S kardo && adduser -S kardo -G kardo && \
    mkdir -p /app/data /tmp && \
    chown -R kardo:kardo /app /tmp

COPY --from=build /build/target/*.jar app.jar

RUN chown -R kardo:kardo /app
USER kardo

# Render inyecta PORT dinámico en tiempo de ejecución.
ENV PORT=8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8 -Djava.awt.headless=true"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
