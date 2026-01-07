# ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB

# Multi-stage build for ESNODE-Core
FROM rust:1-bookworm AS builder
WORKDIR /build
COPY . .
RUN cargo build --release --bin esnode-core

FROM nvidia/cuda:12.4.1-base-ubuntu22.04
WORKDIR /app
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /build/target/release/esnode-core /usr/local/bin/esnode-core
ENV NVIDIA_VISIBLE_DEVICES=all \
    NVIDIA_DRIVER_CAPABILITIES=compute,utility
EXPOSE 9100
ENTRYPOINT ["/usr/local/bin/esnode-core", "daemon"]
