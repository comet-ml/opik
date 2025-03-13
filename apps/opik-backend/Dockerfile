FROM maven:3.9.9-amazoncorretto-21-al2023 AS build

WORKDIR /opt/opik-backend

COPY pom.xml spotless.xml ./
COPY src ./src

ENV MAVEN_OPTS="-Xmx1G -XX:MaxMetaspaceSize=265m"

ARG OPIK_VERSION
ARG MAVEN_CACHE_MOUNT=/root/.m2
RUN --mount=type=cache,target=${MAVEN_CACHE_MOUNT} mvn dependency:go-offline -B
RUN --mount=type=cache,target=${MAVEN_CACHE_MOUNT} mvn versions:set -DnewVersion=${OPIK_VERSION} && \
    mvn clean package -DskipTests

###############################
FROM amazoncorretto:21.0.6-al2023

ARG YUM_CACHE_MOUNT=/var/cache/yum
RUN --mount=type=cache,target=${YUM_CACHE_MOUNT} yum update -y && yum install -y shadow ca-certificates openssl perl \
    && yum clean all
VOLUME /tmp
# Add bundle URL from https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html
ADD https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem /tmp/certs/global-bundle.pem
ADD install_rds_cert.sh .

# A Truststore password needs to be configured and passed as build-arg
ARG STORE_PASSWORD=changeit

# install RDS certificates
RUN chmod 700 install_rds_cert.sh \
    && ./install_rds_cert.sh /tmp/certs/global-bundle.pem $STORE_PASSWORD \
    && rm install_rds_cert.sh /tmp/certs/global-bundle.pem

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

CMD ["./entrypoint.sh"]
