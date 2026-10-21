import os
import redis
import logging
import re
import threading
import time
from urllib.parse import unquote, urlparse

from redis.backoff import FullJitterBackoff
from redis.retry import Retry
from redis.sentinel import Sentinel, SentinelConnectionPool

logger = logging.getLogger(__name__)

_redis_client = None
_master_follower_pid = None

_SENTINEL_SCHEMES = ("redis", "rediss")
_DEFAULT_SENTINEL_PORT = 26379
_MAX_SENTINEL_RETRY_ATTEMPTS = 20
# Same format as the Java backend's Dropwizard durations, e.g. 500ms, 5s, 1m
_DURATION = re.compile(r"^(\d+)\s*([a-z]+)$")
_DURATION_UNITS = {
    "ms": 0.001, "millisecond": 0.001, "milliseconds": 0.001,
    "s": 1, "second": 1, "seconds": 1,
    "m": 60, "min": 60, "mins": 60, "minute": 60, "minutes": 60,
    "h": 3600, "hour": 3600, "hours": 3600,
    "d": 86400, "day": 86400, "days": 86400,
}
_MIN_DURATION_SECONDS = 0.1
_MAX_DURATION_SECONDS = 300


def _create_redis_client_from_env(*, decode_responses: bool = True) -> redis.Redis:
    redis_url = os.getenv("REDIS_URL")
    timeout = float(os.getenv("REDIS_TIMEOUT_SECONDS", "5"))
    health_interval = int(os.getenv("REDIS_HEALTH_CHECK_INTERVAL_SECONDS", "60"))

    if os.getenv("OPIK_REDIS_SENTINEL_ENABLED", "false").strip().lower() == "true":
        if not redis_url:
            raise ValueError("REDIS_URL must point at a sentinel when OPIK_REDIS_SENTINEL_ENABLED is true")
        return _create_sentinel_master_client(
            redis_url,
            decode_responses=decode_responses,
            health_interval=health_interval,
        )

    if redis_url:
        logger.info(f"  Redis: using REDIS_URL")
        return redis.from_url(
            redis_url,
            decode_responses=decode_responses,
            socket_timeout=timeout,
            socket_connect_timeout=timeout,
            health_check_interval=health_interval,
        )
    else:
        # Fall back to individual environment variables
        host = os.getenv("REDIS_HOST", "localhost")
        port = int(os.getenv("REDIS_PORT", "6379"))
        db = int(os.getenv("REDIS_DB", "0"))
        password = os.getenv("REDIS_PASSWORD")

        logger.info(f"  Redis: {host}:{port}/{db}")

        return redis.Redis(
            host=host,
            port=port,
            db=db,
            password=password if password else None,
            decode_responses=decode_responses,
            socket_timeout=timeout,
            socket_connect_timeout=timeout,
            socket_keepalive=True,
            health_check_interval=health_interval,
        )


def _create_sentinel_master_client(
    redis_url: str, *, decode_responses: bool, health_interval: int
) -> redis.Redis:
    """Client for the master that the sentinels currently report, configured exactly like the Java backend.

    REDIS_URL is the seed sentinel: its scheme selects TLS everywhere, and its credentials and database apply to the
    master data nodes. Every new connection asks the sentinels for the master, a connection that lands on a demoted
    master fails with a connection error so the retry reconnects, and _follow_master polls every scan interval.
    """
    seed = urlparse(redis_url)
    if seed.scheme not in _SENTINEL_SCHEMES:
        raise ValueError("REDIS_URL must use the redis or rediss scheme when Sentinel is enabled")
    master_name = os.getenv("OPIK_REDIS_SENTINEL_MASTER_NAME", "").strip()
    if not master_name:
        raise ValueError("OPIK_REDIS_SENTINEL_MASTER_NAME must not be blank when Sentinel is enabled")
    retry_attempts = int(os.getenv("OPIK_REDIS_SENTINEL_RETRY_ATTEMPTS", "3"))
    if not 0 <= retry_attempts <= _MAX_SENTINEL_RETRY_ATTEMPTS:
        raise ValueError(f"OPIK_REDIS_SENTINEL_RETRY_ATTEMPTS must be between 0 and {_MAX_SENTINEL_RETRY_ATTEMPTS}")
    backoff_base = _duration_env("OPIK_REDIS_SENTINEL_RETRY_BACKOFF_BASE", "500ms", minimum=0.001)
    backoff_cap = _duration_env("OPIK_REDIS_SENTINEL_RETRY_BACKOFF_CAP", "2s", minimum=0.001)
    if backoff_base > backoff_cap:
        raise ValueError("OPIK_REDIS_SENTINEL_RETRY_BACKOFF_BASE must not exceed OPIK_REDIS_SENTINEL_RETRY_BACKOFF_CAP")
    connect_timeout = _duration_env("OPIK_REDIS_SENTINEL_CONNECT_TIMEOUT", "10s")
    command_timeout = _duration_env("OPIK_REDIS_SENTINEL_TIMEOUT", "5s")
    scan_interval = _duration_env("OPIK_REDIS_SENTINEL_SCAN_INTERVAL", "2s")

    # Without a port the seed is a sentinel on 26379, not a Redis data node on 6379
    sentinels = [(seed.hostname, seed.port or _DEFAULT_SENTINEL_PORT)]
    for node in os.getenv("OPIK_REDIS_SENTINEL_NODES", "").split(","):
        if node.strip():
            address = _sentinel_address(node.strip(), seed.scheme)
            if address not in sentinels:
                sentinels.append(address)

    ssl = seed.scheme == "rediss"
    check_sentinels = os.getenv("OPIK_REDIS_SENTINEL_CHECK_SENTINELS_LIST", "true").strip().lower() == "true"
    sentinel = Sentinel(
        sentinels,
        # Like the Java backend, refuse a master that no other sentinel agrees on, unless single-sentinel dev setups
        min_other_sentinels=1 if check_sentinels else 0,
        sentinel_kwargs={
            "username": os.getenv("OPIK_REDIS_SENTINEL_USERNAME") or None,
            "password": os.getenv("OPIK_REDIS_SENTINEL_PASSWORD") or None,
            "ssl": ssl,
            "socket_timeout": command_timeout,
            "socket_connect_timeout": connect_timeout,
        },
        username=unquote(seed.username) if seed.username else None,
        password=unquote(seed.password) if seed.password else None,
        ssl=ssl,
    )
    db = int(seed.path.lstrip("/") or 0)
    logger.info(f"  Redis: using Sentinel master '{master_name}' through {len(sentinels)} sentinels, database {db}")
    client = sentinel.master_for(
        master_name,
        db=db,
        decode_responses=decode_responses,
        socket_timeout=command_timeout,
        socket_connect_timeout=connect_timeout,
        health_check_interval=health_interval,
        # Same formula as Redisson's FullJitterDelay: random(0, min(base * 2^attempt, cap))
        retry=Retry(FullJitterBackoff(cap=backoff_cap, base=backoff_base), retry_attempts),
    )
    client.sentinel_scan_interval = scan_interval
    return client


def _follow_master(client: redis.Redis) -> None:
    """Asks the sentinels for the master every scan interval, like Redisson's scanInterval.

    On a change redis-py drops the idle connections, so they reconnect to the new master. Threads do not survive a
    fork, so every process that uses the client starts its own.
    """
    pool = client.connection_pool

    def scan() -> None:
        while True:
            time.sleep(client.sentinel_scan_interval)
            try:
                pool.get_master_address()
            except Exception as error:
                logger.warning(f"Redis Sentinel master scan failed: {error}")

    threading.Thread(target=scan, name="redis-sentinel-master-scan", daemon=True).start()


def _duration_env(
    name: str, default: str, *, minimum: float = _MIN_DURATION_SECONDS, maximum: float = _MAX_DURATION_SECONDS
) -> float:
    value = os.getenv(name) or default
    match = _DURATION.match(value.strip().lower())
    if not match or match.group(2) not in _DURATION_UNITS:
        raise ValueError(f"{name} must be a duration such as 500ms, 5s or 1m")
    seconds = int(match.group(1)) * _DURATION_UNITS[match.group(2)]
    if not minimum <= seconds <= maximum:
        raise ValueError(f"{name} must be between {minimum}s and {maximum}s")
    return seconds


def _sentinel_address(node: str, scheme: str) -> tuple[str, int]:
    # Credentials belong in OPIK_REDIS_SENTINEL_USERNAME/PASSWORD, so errors never echo the entry
    parsed = urlparse(node)
    if parsed.scheme not in _SENTINEL_SCHEMES:
        raise ValueError("OPIK_REDIS_SENTINEL_NODES entries must use the redis or rediss scheme")
    if parsed.scheme != scheme:
        raise ValueError("OPIK_REDIS_SENTINEL_NODES entries must use the same scheme as REDIS_URL")
    if parsed.username or parsed.password:
        raise ValueError(
            "OPIK_REDIS_SENTINEL_NODES entries must not carry credentials, "
            "use OPIK_REDIS_SENTINEL_USERNAME/PASSWORD"
        )
    try:
        port = parsed.port
    except ValueError as exception:
        raise ValueError(
            "OPIK_REDIS_SENTINEL_NODES entries must include a host, and a port between 1 and 65535 if any"
        ) from exception
    if not parsed.hostname:
        raise ValueError("OPIK_REDIS_SENTINEL_NODES entries must include a host, and a port between 1 and 65535 if any")
    return parsed.hostname, port or _DEFAULT_SENTINEL_PORT


def get_redis_client() -> redis.Redis:
    global _redis_client, _master_follower_pid
    if _redis_client is None:
        _redis_client = _create_redis_client_from_env(decode_responses=False)
    if isinstance(_redis_client.connection_pool, SentinelConnectionPool) and _master_follower_pid != os.getpid():
        _master_follower_pid = os.getpid()
        _follow_master(_redis_client)
    return _redis_client
