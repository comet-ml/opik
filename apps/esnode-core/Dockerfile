FROM rust:1.83-slim-bullseye AS builder
WORKDIR /app
COPY . .
# Build the binary
RUN cargo build --release --bin esnode-core -p agent-bin

FROM debian:bullseye-slim
WORKDIR /app
COPY --from=builder /app/target/release/esnode-core /app/esnode-core
# Install dependencies
RUN apt-get update && apt-get install -y libssl1.1 ca-certificates || apt-get install -y libssl-dev ca-certificates && rm -rf /var/lib/apt/lists/*
EXPOSE 9100
ENTRYPOINT ["/app/esnode-core"]
