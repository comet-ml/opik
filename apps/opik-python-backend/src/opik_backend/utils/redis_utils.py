import os
import redis
import logging

from redis.backoff import FullJitterBackoff
from redis.retry import Retry
from redis.sentinel import Sentinel

from opik_backend.utils.redis_sentinel_settings import (  # noqa: F401 - RedisSentinelConfigError is re-exported
    RedisSentinelConfigError,
    RedisSentinelSettings,
    is_sentinel_enabled,
)

logger = logging.getLogger(__name__)

_redis_client = None


def _create_redis_client_from_env(*, decode_responses: bool = True) -> redis.Redis:
    redis_url = os.getenv("REDIS_URL")
    timeout = float(os.getenv("REDIS_TIMEOUT_SECONDS", "5"))
    health_interval = int(os.getenv("REDIS_HEALTH_CHECK_INTERVAL_SECONDS", "60"))

    if is_sentinel_enabled():
        return _create_sentinel_master_client(
            RedisSentinelSettings.from_env(),
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
    settings: RedisSentinelSettings, *, decode_responses: bool, health_interval: int
) -> redis.Redis:
    """Client for the master the sentinels currently report.

    redis-py follows a failover on its own: every new connection asks the sentinels for the master, and a connection
    that lands on a demoted master fails with a connection error, so the retry reconnects to the promoted one.
    """
    sentinel = Sentinel(
        settings.sentinels,
        # Like the Java backend, refuse a master that no other sentinel agrees on, unless single-sentinel dev setups
        min_other_sentinels=1 if settings.check_sentinels_list else 0,
        sentinel_kwargs={
            "username": settings.sentinel_username,
            "password": settings.sentinel_password,
            "ssl": settings.ssl,
            "socket_timeout": settings.timeout,
            "socket_connect_timeout": settings.connect_timeout,
        },
        username=settings.username,
        password=settings.password,
        ssl=settings.ssl,
    )
    logger.info(f"  Redis: using Sentinel master '{settings.master_name}' through {len(settings.sentinels)} sentinels, "
                f"database {settings.db}")
    return sentinel.master_for(
        settings.master_name,
        db=settings.db,
        decode_responses=decode_responses,
        socket_timeout=settings.timeout,
        socket_connect_timeout=settings.connect_timeout,
        health_check_interval=health_interval,
        # Same formula as Redisson's FullJitterDelay: random(0, min(base * 2^attempt, cap))
        retry=Retry(FullJitterBackoff(cap=settings.retry_backoff_cap, base=settings.retry_backoff_base),
                    settings.retry_attempts),
    )


def get_redis_client() -> redis.Redis:
    global _redis_client
    if _redis_client is None:
        _redis_client = _create_redis_client_from_env(decode_responses=False)
    return _redis_client
