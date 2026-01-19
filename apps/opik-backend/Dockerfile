FROM maven:3.9.11-amazoncorretto-21-al2023 AS build

WORKDIR /opt/opik-backend

# Copy parent POM first for dependency caching
COPY pom.xml spotless.xml ./
RUN mvn dependency:go-offline

# Copy telemetry extension POM and download its dependencies
COPY opik-telemetry-extension/pom.xml opik-telemetry-extension/pom.xml
RUN cd opik-telemetry-extension && mvn dependency:go-offline

# Copy source code
COPY src ./src
COPY opik-telemetry-extension ./opik-telemetry-extension

# Build both artifacts
ARG OPIK_VERSION
ENV MAVEN_OPTS="-Xmx1G -XX:MaxMetaspaceSize=265m"
RUN mvn versions:set -DnewVersion=${OPIK_VERSION} && \
    mvn clean package -DskipTests && \
    cd opik-telemetry-extension && \
    mvn versions:set -DnewVersion=${OPIK_VERSION} && \
    mvn clean package -DskipTests

###############################
FROM amazoncorretto:21.0.8-al2023

# Add metadata labels
LABEL org.opencontainers.image.title="Opik Backend"
LABEL org.opencontainers.image.description="Opik Backend Service"
LABEL org.opencontainers.image.vendor="Comet ML"

# Install dependencies, download and verify AWS RDS certificate bundle
# SHA256 checksum must match the official AWS RDS global bundle
# Update this checksum when AWS updates the bundle: https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
ARG RDS_CERT_SHA256=e5bb2084ccf45087bda1c9bffdea0eb15ee67f0b91646106e466714f9de3c7e3
ARG STORE_PASSWORD=changeit
COPY install_rds_cert.sh /tmp/install_rds_cert.sh
RUN yum update -y && \
    yum install -y --allowerasing shadow ca-certificates openssl perl dos2unix curl && \
    yum clean all && \
    rm -rf /var/cache/yum && \
    mkdir -p /tmp/certs && \
    curl -fsSL -o /tmp/certs/global-bundle.pem https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem && \
    echo "${RDS_CERT_SHA256}  /tmp/certs/global-bundle.pem" | sha256sum -c - && \
    dos2unix /tmp/install_rds_cert.sh && \
    chmod 700 /tmp/install_rds_cert.sh && \
    /tmp/install_rds_cert.sh /tmp/certs/global-bundle.pem $STORE_PASSWORD && \
    rm -f /tmp/install_rds_cert.sh /tmp/certs/global-bundle.pem && \
    rmdir /tmp/certs

# Set up application directory
WORKDIR /opt/opik

# Copy application files
COPY --chown=1001:1001 config.yml lombok.config entrypoint.sh run_db_migrations.sh ./
COPY --chown=1001:1001 redoc/ redoc/

# Prepare shell scripts
RUN dos2unix ./*.sh && chmod +x ./*.sh

# Copy built artifacts from build stage
COPY --from=build --chown=1001:1001 /opt/opik-backend/target/openapi.yaml redoc/
COPY --from=build --chown=1001:1001 /opt/opik-backend/target/*.jar ./
COPY --from=build --chown=1001:1001 /opt/opik-backend/opik-telemetry-extension/target/opik-telemetry-extension-*.jar ./opik-telemetry-extension.jar

# Set environment variables
ARG OPIK_VERSION
ENV OPIK_VERSION=${OPIK_VERSION}

# Expose ports
EXPOSE 8080
EXPOSE 3003

# Set /tmp permissions with sticky bit (more secure than 777)
RUN chmod 1777 /tmp

# Switch to non-root user
USER 1001:1001

CMD ["./entrypoint.sh"]