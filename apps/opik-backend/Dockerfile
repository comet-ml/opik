FROM maven:3.10.0-amazoncorretto-25-al2023@sha256:d96c6ff2e7a3258e6f0c447340d2284a3404917bb8c5f01c8660cc4066bd382c AS build

WORKDIR /opt/opik-backend

# Copy parent POM first for dependency caching. lombok.config belongs here, where the code compiles: without it
# Lombok drops copyable annotations such as @Config from generated @Inject constructors, and Guice injects an empty
# config object instead of the configured one.
COPY pom.xml spotless.xml lombok.config ./
RUN mvn dependency:go-offline

# Copy source code
COPY src ./src

# Build artifact
ARG OPIK_VERSION
ENV MAVEN_OPTS="-Xmx1G -XX:MaxMetaspaceSize=265m"
# The image only needs the shaded main jar. maven.test.skip drops test
# compilation (nothing consumes a test-jar), and source/javadoc skip drops
# artifacts the runtime image never uses — trimming the package stage without
# changing what ships. (-T is a no-op here: single-module build, no reactor.)
RUN mvn versions:set -DnewVersion=${OPIK_VERSION} && \
    mvn clean package -Dmaven.test.skip=true -Dmaven.source.skip=true -Dmaven.javadoc.skip=true -Dspotless.skip=true

###############################
FROM amazoncorretto:25.0.5-al2023@sha256:be686934052c72a4ff4b087a504af6bf4fb8576eaf4a7bfb7f61b698269abc32

# Add metadata labels
LABEL org.opencontainers.image.title="Opik Backend"
LABEL org.opencontainers.image.description="Opik Backend Service"
LABEL org.opencontainers.image.vendor="Comet ML"

# Install dependencies, download and verify AWS RDS certificate bundle
# SHA256 checksum must match the official AWS RDS global bundle
# Update this checksum when AWS updates the bundle: https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
ARG RDS_CERT_SHA256=90a6f1a7978a1b1b096dca6c57511ca48d3b8cf052f8e704baf358f34ea2f21b
# Not a secret: `changeit` is the stock JDK truststore password, and this truststore holds only
# public AWS RDS CA certificates. Flagged because the variable name matches hadolint's heuristic.
# hadolint ignore=DL3064
ARG STORE_PASSWORD=changeit
COPY install_rds_cert.sh /tmp/install_rds_cert.sh
# perl is intentionally not installed: it pulls in perl-Archive-Tar, which is
# affected by CVE-2026-9538 (ALAS2023-2026-1805) and has no patched AL2023 RPM.
# install_rds_cert.sh uses sed/openssl instead of perl to extract the cert CN.
# pipefail so the `sha256sum -c` failing inside the piped RUN below aborts the build.
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
# DL3033: AL2023 core packages track a rolling security channel; pinning exact
# RPM versions here would rot as AWS updates the base image's repos.
# --releasever=latest: AL2023 locks yum to the base image's release, so a plain
# update never reaches fixes published after the amazoncorretto image was built.
# hadolint ignore=DL3033
RUN yum upgrade -y --releasever=latest && \
    yum install -y --allowerasing shadow ca-certificates openssl dos2unix curl && \
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
COPY --chown=1001:1001 config.yml lombok.config entrypoint.sh run_db_migrations.sh rebaseline_db_changelog.sh provision_agent_insights_readonly_user.sh opik-otel-views.yaml ./
COPY --chown=1001:1001 redoc/ redoc/

# Prepare shell scripts
RUN dos2unix ./*.sh && chmod +x ./*.sh

# Copy built artifacts from build stage
COPY --from=build --chown=1001:1001 /opt/opik-backend/target/openapi.yaml redoc/
COPY --from=build --chown=1001:1001 /opt/opik-backend/target/*.jar ./

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