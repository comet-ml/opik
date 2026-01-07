# Container images for ESNODE-Core

## Minimal distroless image (Phase 1)
- Dockerfile: `deploy/docker/Dockerfile.distroless`
- Runtime: `gcr.io/distroless/static:nonroot`, non-root user, exposed port `9100`, no init/systemd.
- Expected input: a prebuilt static (musl) binary named `esnode-core` in the build context (or point `ESNODE_BINARY` to your binary path).

### Build (single arch)
```bash
# Build a static binary first (example for amd64):
cargo build --release --locked --target x86_64-unknown-linux-musl
# Copy/rename into build context root as esnode-core, then:
docker build -f deploy/docker/Dockerfile.distroless -t esnode-core:local .
```

### Build multi-arch (amd64 + arm64) with buildx
```bash
# Prepare per-arch binaries before building:
#   target/x86_64-unknown-linux-musl/release/esnode-core
#   target/aarch64-unknown-linux-musl/release/esnode-core
docker buildx create --use --name esnode-builder || true
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -f deploy/docker/Dockerfile.distroless \
  -t ghcr.io/ESNODE/esnode-core:0.1.0 \
  -t ghcr.io/ESNODE/esnode-core:latest \
  --push .
```

> Note: distroless expects static binaries; ensure NVML/libnvidia-ml.so is available via the NVIDIA runtime/host drivers when running with GPU access.

### Run (basic)
```bash
docker run --rm --net=host --pid=host ghcr.io/ESNODE/esnode-core:0.1.0
```

Adjust mounts/privileges for GPU telemetry (e.g., NVIDIA Container Toolkit) and `/sys` access as needed.

## Registry logins (local/CI)
- GitHub Container Registry (ghcr.io):
  - Create a PAT with `write:packages`.
  - Login locally: `echo "$GHCR_PAT" | docker login ghcr.io -u "$GHCR_USER" --password-stdin`.
  - In GitHub Actions: store the PAT as a secret (e.g., `CR_PAT`) and login similarly.
- Docker Hub:
  - Create an access token.
  - Login locally: `echo "$DOCKERHUB_TOKEN" | docker login -u "$DOCKERHUB_USER" --password-stdin`.
  - In CI: store as secrets and login the same way.

See `.env.docker.example` for a template of local env vars (copy to `.env.docker` and fill; do not commit secrets).

## Release workflow (automated images)
- `.github/workflows/release.yml` builds binaries, packages artifacts, and now builds multi-arch images from the published tarballs:
  - Uses `deploy/docker/Dockerfile.multi` with pre-extracted binaries named `esnode-core-amd64` and `esnode-core-arm64`.
  - Pushes to `ghcr.io/esnode/esnode-core:<tag>` and `:latest`.
  - Optionally pushes to Docker Hub `docker.io/esnode/esnode-core` when `DOCKERHUB_USER`/`DOCKERHUB_TOKEN` secrets are set.
- Ensure GHCR has write permission for the GitHub token, or set a PAT with `write:packages` if needed.
