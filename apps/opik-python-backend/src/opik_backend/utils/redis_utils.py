import os
import redis
import logging

logger = logging.getLogger(__name__)

_redis_client = None

def _create_redis_client_from_env(*, decode_responses: bool = True) -> redis.Redis:
    redis_url = os.getenv("REDIS_URL")
    timeout = float(os.getenv("REDIS_TIMEOUT_SECONDS", "5"))
    health_interval = int(os.getenv("REDIS_HEALTH_CHECK_INTERVAL_SECONDS", "60"))
    
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

def get_redis_client() -> redis.Redis:
    global _redis_client
    if _redis_client is None:
        _redis_client = _create_redis_client_from_env(decode_responses=False)
    return _redis_client


