FROM maven:3.9.9-amazoncorretto-21-alpine AS build

WORKDIR /opt/opik-backend

COPY pom.xml spotless.xml ./
COPY src ./src

ENV MAVEN_OPTS="-Xmx1G -XX:MaxMetaspaceSize=265m"

ARG OPIK_VERSION
RUN mvn versions:set -DnewVersion=${OPIK_VERSION} && \
    mvn clean package -DskipTests

###############################
FROM amazoncorretto:21-alpine3.20

# Update the package list and install necessary packages
RUN apk update && \
    apk add --no-cache shadow ca-certificates openssl perl \
    && update-ca-certificates \
    && rm -rf /var/cache/apk/* \

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
COPY config.yml entrypoint.sh run_db_migrations.sh ./
COPY redoc/ redoc/

RUN chmod +x ./*.sh

# Set opik version
ARG OPIK_VERSION
ENV OPIK_VERSION=${OPIK_VERSION}

COPY --from=build /opt/opik-backend/target/openapi.yaml redoc/
COPY --from=build /opt/opik-backend/target/opik-backend-$OPIK_VERSION.jar ./

EXPOSE 8080
EXPOSE 3003

CMD ["/bin/sh", "entrypoint.sh"]
