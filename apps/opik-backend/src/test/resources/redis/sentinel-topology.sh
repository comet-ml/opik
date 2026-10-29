#!/bin/sh
# A master, a replica and one sentinel in one container, for RedisContainerUtils.newSentinelContainer.
# Ports and the master name come from the container environment, set per test run.
set -e
redis-server --port "$MASTER_PORT" --protected-mode no --daemonize yes
redis-server --port "$REPLICA_PORT" --protected-mode no --replicaof 127.0.0.1 "$MASTER_PORT" --daemonize yes
cat > /tmp/sentinel.conf <<CONF
port $SENTINEL_PORT
protected-mode no
sentinel monitor $MASTER_NAME 127.0.0.1 $MASTER_PORT 1
CONF
exec redis-sentinel /tmp/sentinel.conf
