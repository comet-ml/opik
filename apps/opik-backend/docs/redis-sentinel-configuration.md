# Redis Sentinel Configuration

The backend can connect to Redis either directly (single node, the default) or through
[Redis Sentinel](https://redis.io/docs/latest/operate/oss_and_stack/management/sentinel/) for high availability. In
sentinel mode Redisson resolves the current master through the sentinels and reconnects to the promoted replica on
failover, transparently to the rest of the application.

Everything is driven by the `redis` block of `config.yml`, and every field has an environment variable override.

## Single node mode (default)

```yaml
redis:
  singleNodeUrl: "redis://:password@redis-host:6379/0"
```

## Sentinel mode

```yaml
redis:
  # Points at a sentinel, not at a data node
  singleNodeUrl: "redis://:password@sentinel-1:26379/0"
  sentinel:
    enabled: true
    masterName: "mymaster"
    nodes: "redis://sentinel-2:26379,redis://sentinel-3:26379"
```

Or purely through environment variables:

```bash
REDIS_URL="redis://:password@sentinel-1:26379/0"
OPIK_REDIS_SENTINEL_ENABLED=true
OPIK_REDIS_SENTINEL_MASTER_NAME=mymaster
OPIK_REDIS_SENTINEL_NODES="redis://sentinel-2:26379,redis://sentinel-3:26379"
```

### How `singleNodeUrl` is interpreted

In sentinel mode the same URL is reused, but its parts mean different things:

| Part | Meaning in sentinel mode |
|------|--------------------------|
| Scheme (`redis://` / `rediss://`) | Whether the **sentinel** connections use TLS |
| Host and port | The seed sentinel address, usually port `26379` |
| Username and password | Credentials for the **master data nodes**, not for the sentinels |
| Database number | Database selected on the master after connecting |

Credentials for the sentinels themselves are separate, see `sentinel.username` / `sentinel.password` below.

### Options

| Property | Environment variable | Default | Description |
|----------|---------------------|---------|-------------|
| `sentinel.enabled` | `OPIK_REDIS_SENTINEL_ENABLED` | `false` | Enables sentinel mode |
| `sentinel.masterName` | `OPIK_REDIS_SENTINEL_MASTER_NAME` | — | Name of the monitored master group. **Required** when enabled |
| `sentinel.nodes` | `OPIK_REDIS_SENTINEL_NODES` | `''` | Comma separated extra seed sentinels, on top of the one from `singleNodeUrl` |
| `sentinel.username` | `OPIK_REDIS_SENTINEL_USERNAME` | `''` | Username for the sentinel nodes, if they enforce ACLs |
| `sentinel.password` | `OPIK_REDIS_SENTINEL_PASSWORD` | `''` | Password for the sentinel nodes, if they require auth |
| `sentinel.retryAttempts` | `OPIK_REDIS_SENTINEL_RETRY_ATTEMPTS` | `3` | Retries for a failed command while a failover is in progress |
| `sentinel.checkSentinelsList` | `OPIK_REDIS_SENTINEL_CHECK_SENTINELS_LIST` | `true` | Require the sentinels to report at least two nodes |
| `sentinel.connectTimeout` | `OPIK_REDIS_SENTINEL_CONNECT_TIMEOUT` | `10s` | Timeout for establishing connections |
| `sentinel.timeout` | `OPIK_REDIS_SENTINEL_TIMEOUT` | `5s` | Timeout for command responses |
| `sentinel.scanInterval` | `OPIK_REDIS_SENTINEL_SCAN_INTERVAL` | `2s` | How often the sentinels are polled for topology changes |

### Seed sentinels

Only one sentinel address is strictly required: Redisson discovers the rest of the topology from whichever sentinel it
reaches first. Listing the other sentinels in `sentinel.nodes` means startup does not depend on one specific sentinel
being reachable, which matters when the backend restarts during a partial outage.

The address derived from `singleNodeUrl` is always tried first, followed by `sentinel.nodes` in order. Duplicates are
ignored.

### Minimum number of sentinels

By default Redisson refuses to start unless the sentinels report at least two nodes, which is the right guard for a
production deployment: a quorum of three sentinels is the usual recommendation, since a single sentinel is both a single
point of failure and unable to establish a quorum for a failover.

If the sentinels report fewer than two nodes, startup fails with:

```
SENTINEL SENTINELS command returns less than 2 nodes or connection can't be established to some of them!
```

For a development setup with a single sentinel, set `sentinel.checkSentinelsList` to `false`.

## Failover behaviour

1. The sentinels stop receiving replies from the master and, after `down-after-milliseconds`, flag it subjectively down.
2. Once a quorum agrees, the master is flagged objectively down and a failover starts.
3. A replica is promoted to master.
4. Redisson learns the new address through the sentinel `+switch-master` pub/sub channel, and also picks up topology
   changes by polling every `scanInterval`.
5. Redisson reconnects to the new master. Commands in flight during the switch are retried up to `retryAttempts`.

Commands issued during the window between the master failing and the replica being promoted will fail. Sizing
`retryAttempts` and `timeout` against the sentinels' `down-after-milliseconds` and `failover-timeout` determines how much
of that window is absorbed transparently.

## TLS

The scheme of `singleNodeUrl` applies to both the sentinel and the master connections. A topology where the sentinels
are plaintext but the data nodes require TLS (or the reverse) is therefore not expressible with this configuration.

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Master node is undefined! SENTINEL GET-MASTER-ADDR-BY-NAME command returns empty result!` | `masterName` does not match a monitored group | Run `SENTINEL masters` against a sentinel and use the reported `name` |
| `SENTINEL SENTINELS command returns less than 2 nodes...` | Fewer than two sentinels reachable | Run a quorum of sentinels, or set `checkSentinelsList: false` for development |
| `Unable to connect to: rediss://host:26379`, with an SSL handshake timeout | Sentinels do not speak TLS | Use the `redis://` scheme in `singleNodeUrl` |
| `Certificate for <ip> doesn't match any of the subject alternative names` | Sentinels announce IPs, which fail hostname verification | Configure `sentinel announce-hostnames yes` on the sentinels, and use hostnames in `singleNodeUrl` |
| Backend starts, then loses Redis after a failover and does not recover | The addresses announced by the sentinels are not routable from the backend | Make sure the sentinels announce addresses reachable by the backend, e.g. via `announce-ip` / `announce-hostnames` |

Useful commands against a sentinel:

```bash
redis-cli -h <sentinel-host> -p 26379 SENTINEL masters
redis-cli -h <sentinel-host> -p 26379 SENTINEL get-master-addr-by-name <masterName>
redis-cli -h <sentinel-host> -p 26379 SENTINEL sentinels <masterName>
```

## Verifying

`GET /health-check` reports `503` while Redis is unreachable, so it reflects whether master discovery succeeded. Redis
is on the path of rate limiting, distributed locks, caching, alert buckets and event streams, so any authenticated API
call exercises the connection as well.

## Implementation

`RedisConfig.build()` produces either a single server or a sentinel Redisson `Config`, which `RedisModule` uses to
create the `RedissonClient` singleton. Coverage lives in `RedisConfigTest` (config building) and
`RedisSentinelConfigIntegrationTest` (master discovery against a real master, replica and sentinel topology).

