#!/usr/bin/env bash
#
# Optional operator convenience: run `clickhouse-client` from the official ClickHouse image, for a host that has Docker
# but no native client installed. It connects over the network to whatever the standard CLICKHOUSE_* env points at (a
# production cluster, or a locally-exposed port in a rehearsal), exactly as a native client would.
#
# A host with a native `clickhouse-client` should use that and ignore this wrapper. To use the wrapper, symlink it onto
# PATH under the name the driver scripts invoke:
#   ln -s "$PWD/clickhouse-client-docker.sh" ~/bin/clickhouse-client   # ensure ~/bin is on PATH
#
# Knobs (env):
#   CLICKHOUSE_CLIENT_IMAGE        official image to run — match your server's major version. Default:
#                                  clickhouse/clickhouse-server:26.3.16.16-alpine (the version Opik ships); its bundled
#                                  clickhouse-client is used via --entrypoint, so there is one trusted image.
#   CLICKHOUSE_CLIENT_DOCKER_OPTS  extra `docker run` flags. Empty for a remote CLICKHOUSE_HOST (the container dials out).
#                                  To reach a ClickHouse on the HOST's own loopback there are two cases, and on macOS they
#                                  are NOT interchangeable:
#                                    * Linux, or a container publishing its port (e.g. a local `opik.sh --port-mapping`):
#                                      '--network=host' works.
#                                    * macOS + `kubectl port-forward` (the normal way to reach a real cluster): use
#                                        CLICKHOUSE_CLIENT_DOCKER_OPTS=--add-host=host.docker.internal:host-gateway
#                                        CLICKHOUSE_HOST=host.docker.internal
#                                      '--network=host' does NOT work here: inside Docker Desktop the "host" network is
#                                      the Docker VM, not your Mac, so it cannot see a port-forward bound to the Mac's
#                                      loopback (you get "Connection refused").
#                                  Remember the port itself must go through the drivers' --port flag — clickhouse-client
#                                  ignores CLICKHOUSE_PORT.
set -euo pipefail

IMAGE="${CLICKHOUSE_CLIENT_IMAGE:-clickhouse/clickhouse-server:26.3.16.16-alpine}"

# CLICKHOUSE_CLIENT_DOCKER_OPTS is unquoted so multiple flags word-split; CLICKHOUSE_* are forwarded so the in-image
# client reads the same connection env the driver scripts set.
exec docker run --rm -i ${CLICKHOUSE_CLIENT_DOCKER_OPTS:-} \
    -e CLICKHOUSE_HOST -e CLICKHOUSE_PORT -e CLICKHOUSE_USER -e CLICKHOUSE_PASSWORD -e CLICKHOUSE_DATABASE \
    --entrypoint clickhouse-client "$IMAGE" "$@"
