FROM maven:3.9.11-amazoncorretto-21-al2023 AS build

WORKDIR /opt/opik-backend

COPY pom.xml spotless.xml ./
COPY src ./src
COPY opik-telemetry-extension ./opik-telemetry-extension

# Build telemetry extension first
WORKDIR /opt/opik-backend/opik-telemetry-extension
RUN mvn clean package -DskipTests

# Build main application
WORKDIR /opt/opik-backend
ENV MAVEN_OPTS="-Xmx1G -XX:MaxMetaspaceSize=265m"

ARG OPIK_VERSION
RUN mvn versions:set -DnewVersion=${OPIK_VERSION} && \
    mvn clean package -DskipTests

###############################
FROM amazoncorretto:21.0.8-al2023

RUN yum update -y && yum install -y shadow ca-certificates openssl perl dos2unix \
    && yum clean all
VOLUME /tmp
# Add bundle URL from https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html
ADD https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem /tmp/certs/global-bundle.pem
ADD install_rds_cert.sh .

# A Truststore password needs to be configured and passed as build-arg
ARG STORE_PASSWORD=changeit

# install RDS certificates
RUN dos2unix install_rds_cert.sh \
    && chmod 700 install_rds_cert.sh \
    && ./install_rds_cert.sh /tmp/certs/global-bundle.pem $STORE_PASSWORD \
    && rm install_rds_cert.sh /tmp/certs/global-bundle.pem

WORKDIR /opt/opik
COPY config.yml lombok.config entrypoint.sh run_db_migrations.sh ./
COPY redoc/ redoc/

RUN dos2unix ./*.sh
RUN chmod +x ./*.sh

COPY --from=build /opt/opik-backend/target/openapi.yaml redoc/
COPY --from=build /opt/opik-backend/target/*.jar ./
# Copy telemetry extension JAR (built as part of the build process)
COPY --from=build /opt/opik-backend/opik-telemetry-extension/target/opik-telemetry-extension-*.jar ./opik-telemetry-extension.jar

EXPOSE 8080
EXPOSE 3003

ARG OPIK_VERSION
ENV OPIK_VERSION=${OPIK_VERSION}

RUN chmod -R 777 /tmp && chown -R 1001:1001 /opt/opik
USER 1001:1001

CMD ["./entrypoint.sh"]
