import os
import redis

_redis_client = None


def _create_redis_client_from_env(*, decode_responses: bool = True) -> redis.Redis:
    host = os.getenv("REDIS_HOST", "localhost")
    port = int(os.getenv("REDIS_PORT", "6379"))
    db = int(os.getenv("REDIS_DB", "0"))
    password = os.getenv("REDIS_PASSWORD")
    timeout = float(os.getenv("REDIS_TIMEOUT_SECONDS", "5"))
    health_interval = int(os.getenv("REDIS_HEALTH_CHECK_INTERVAL_SECONDS", "60"))

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


def get_redis_client() -> redis.Redis:
    global _redis_client
    if _redis_client is None:
        _redis_client = _create_redis_client_from_env(decode_responses=True)
    return _redis_client


