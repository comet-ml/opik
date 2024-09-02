FROM maven:3.9.8-amazoncorretto-21-al2023 AS build

WORKDIR /opt/opik-backend

COPY pom.xml spotless.xml ./
COPY src ./src

ENV MAVEN_OPTS="-Xmx1G -XX:MaxMetaspaceSize=265m"

ARG OPIK_VERSION
RUN mvn versions:set -DnewVersion=${OPIK_VERSION} && \
    mvn clean package -DskipTests

###############################
FROM amazoncorretto:21-al2023

WORKDIR /opt/opik
COPY config.yml lombok.config entrypoint.sh run_db_migrations.sh ./
COPY redoc/ redoc/

RUN chmod +x ./*.sh
COPY --from=build /opt/opik-backend/target/openapi.yaml redoc/
COPY --from=build /opt/opik-backend/target/*.jar ./

EXPOSE 8080
EXPOSE 3003

ARG OPIK_VERSION
ENV OPIK_VERSION=${OPIK_VERSION}

ENTRYPOINT ["./entrypoint.sh"]
