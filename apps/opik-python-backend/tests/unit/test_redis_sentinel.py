"""Sentinel mode reads the same REDIS_URL and OPIK_REDIS_SENTINEL_* variables as the Java backend."""
import secrets

import pytest
from redis.backoff import FullJitterBackoff

from opik_backend.utils import redis_utils

SENTINEL_VARIABLES = (
    "REDIS_URL",
    "OPIK_REDIS_SENTINEL_ENABLED",
    "OPIK_REDIS_SENTINEL_MASTER_NAME",
    "OPIK_REDIS_SENTINEL_NODES",
    "OPIK_REDIS_SENTINEL_USERNAME",
    "OPIK_REDIS_SENTINEL_PASSWORD",
    "OPIK_REDIS_SENTINEL_RETRY_ATTEMPTS",
    "OPIK_REDIS_SENTINEL_CHECK_SENTINELS_LIST",
    "OPIK_REDIS_SENTINEL_RETRY_BACKOFF_BASE",
    "OPIK_REDIS_SENTINEL_RETRY_BACKOFF_CAP",
    "OPIK_REDIS_SENTINEL_CONNECT_TIMEOUT",
    "OPIK_REDIS_SENTINEL_TIMEOUT",
    "OPIK_REDIS_SENTINEL_SCAN_INTERVAL",
)


@pytest.fixture
def sentinel_env(monkeypatch):
    for variable in SENTINEL_VARIABLES:
        monkeypatch.delenv(variable, raising=False)
    master_name = f"master-{secrets.token_hex(4)}"
    monkeypatch.setenv("REDIS_URL", "redis://data-user:data%40pass@sentinel-1:26379/3")
    monkeypatch.setenv("OPIK_REDIS_SENTINEL_ENABLED", "true")
    monkeypatch.setenv("OPIK_REDIS_SENTINEL_MASTER_NAME", f" {master_name} ")
    return monkeypatch, master_name


def _sentinel_view(client):
    pool = client.connection_pool
    manager = pool.sentinel_manager
    return {
        "service_name": pool.service_name,
        "master_connection": pool.connection_class.__name__,
        "master": {
            key: pool.connection_kwargs[key]
            for key in ("username", "password", "db", "decode_responses", "socket_connect_timeout", "socket_timeout")
        },
        "retries": pool.connection_kwargs["retry"]._retries,
        "backoff": pool.connection_kwargs["retry"]._backoff,
        "scan_interval": client.sentinel_scan_interval,
        "min_other_sentinels": manager.min_other_sentinels,
        "sentinels": [
            {
                key: sentinel.connection_pool.connection_kwargs[key]
                for key in ("host", "port", "username", "password", "socket_connect_timeout", "socket_timeout")
            }
            | {"connection": sentinel.connection_pool.connection_class.__name__}
            for sentinel in manager.sentinels
        ],
    }


def test_builds_sentinel_master_client_from_environment(sentinel_env):
    monkeypatch, master_name = sentinel_env
    monkeypatch.setenv("REDIS_URL", "rediss://data-user:data%40pass@sentinel-1:26379/3")
    # Padded, with an empty entry and a duplicate of the seed, which must collapse
    monkeypatch.setenv("OPIK_REDIS_SENTINEL_NODES", " rediss://sentinel-2:26380 , ,rediss://sentinel-1:26379")
    monkeypatch.setenv("OPIK_REDIS_SENTINEL_USERNAME", "sentinel-user")
    monkeypatch.setenv("OPIK_REDIS_SENTINEL_PASSWORD", "sentinel-pass")
    monkeypatch.setenv("OPIK_REDIS_SENTINEL_RETRY_ATTEMPTS", "7")
    monkeypatch.setenv("OPIK_REDIS_SENTINEL_CHECK_SENTINELS_LIST", "false")
    monkeypatch.setenv("OPIK_REDIS_SENTINEL_RETRY_BACKOFF_BASE", "300ms")
    monkeypatch.setenv("OPIK_REDIS_SENTINEL_RETRY_BACKOFF_CAP", "4s")
    monkeypatch.setenv("OPIK_REDIS_SENTINEL_CONNECT_TIMEOUT", "11s")
    monkeypatch.setenv("OPIK_REDIS_SENTINEL_TIMEOUT", "6s")
    monkeypatch.setenv("OPIK_REDIS_SENTINEL_SCAN_INTERVAL", "1500ms")

    client = redis_utils._create_redis_client_from_env(decode_responses=False)

    sentinel = {
        "username": "sentinel-user",
        "password": "sentinel-pass",
        "socket_connect_timeout": 11,
        "socket_timeout": 6,
        "connection": "SSLConnection",
    }
    assert _sentinel_view(client) == {
        "service_name": master_name,
        "master_connection": "SentinelManagedSSLConnection",
        "master": {
            "username": "data-user",
            "password": "data@pass",
            "db": 3,
            "decode_responses": False,
            "socket_connect_timeout": 11,
            "socket_timeout": 6,
        },
        "retries": 7,
        "backoff": FullJitterBackoff(cap=4, base=0.3),
        "scan_interval": 1.5,
        "min_other_sentinels": 0,
        "sentinels": [
            {"host": "sentinel-1", "port": 26379, **sentinel},
            {"host": "sentinel-2", "port": 26380, **sentinel},
        ],
    }


def test_applies_java_backend_defaults(sentinel_env):
    monkeypatch, master_name = sentinel_env
    # An exported but empty variable must behave like an unset one
    monkeypatch.setenv("OPIK_REDIS_SENTINEL_NODES", "")

    client = redis_utils._create_redis_client_from_env(decode_responses=True)

    assert _sentinel_view(client) == {
        "service_name": master_name,
        "master_connection": "SentinelManagedConnection",
        "master": {
            "username": "data-user",
            "password": "data@pass",
            "db": 3,
            "decode_responses": True,
            "socket_connect_timeout": 10,
            "socket_timeout": 5,
        },
        "retries": 3,
        "backoff": FullJitterBackoff(cap=2, base=0.5),
        "scan_interval": 2,
        "min_other_sentinels": 1,
        "sentinels": [
            {
                "host": "sentinel-1",
                "port": 26379,
                "username": None,
                "password": None,
                "socket_connect_timeout": 10,
                "socket_timeout": 5,
                "connection": "Connection",
            },
        ],
    }


def test_keeps_direct_client_when_sentinel_is_disabled(sentinel_env):
    monkeypatch, _ = sentinel_env
    monkeypatch.setenv("OPIK_REDIS_SENTINEL_ENABLED", "false")

    client = redis_utils._create_redis_client_from_env(decode_responses=False)

    assert type(client.connection_pool).__name__ == "ConnectionPool"
    assert client.connection_pool.connection_kwargs["host"] == "sentinel-1"


@pytest.mark.parametrize(
    "variable, value, message",
    [
        ("OPIK_REDIS_SENTINEL_MASTER_NAME", " ", "OPIK_REDIS_SENTINEL_MASTER_NAME must not be blank"),
        ("OPIK_REDIS_SENTINEL_RETRY_ATTEMPTS", "21", "OPIK_REDIS_SENTINEL_RETRY_ATTEMPTS must be between 0 and 20"),
        ("OPIK_REDIS_SENTINEL_RETRY_BACKOFF_BASE", "3s", "must not exceed OPIK_REDIS_SENTINEL_RETRY_BACKOFF_CAP"),
        ("OPIK_REDIS_SENTINEL_RETRY_BACKOFF_CAP", "6m", "OPIK_REDIS_SENTINEL_RETRY_BACKOFF_CAP must be between"),
        ("OPIK_REDIS_SENTINEL_CONNECT_TIMEOUT", "6m", "OPIK_REDIS_SENTINEL_CONNECT_TIMEOUT must be between"),
        ("OPIK_REDIS_SENTINEL_TIMEOUT", "5", "OPIK_REDIS_SENTINEL_TIMEOUT must be a duration"),
        ("OPIK_REDIS_SENTINEL_SCAN_INTERVAL", "50ms", "OPIK_REDIS_SENTINEL_SCAN_INTERVAL must be between"),
        ("REDIS_URL", "http://sentinel-1:26379/0", "REDIS_URL must use the redis or rediss scheme"),
        ("OPIK_REDIS_SENTINEL_NODES", "rediss://sentinel-2:26379", "must use the same scheme as REDIS_URL"),
        ("OPIK_REDIS_SENTINEL_NODES", "http://sentinel-2:26379", "must use the redis or rediss scheme"),
        ("OPIK_REDIS_SENTINEL_NODES", "redis://user:secret@sentinel-2:26379", "must not carry credentials"),
        ("OPIK_REDIS_SENTINEL_NODES", "redis://sentinel-2", "must include a host and a port"),
        ("OPIK_REDIS_SENTINEL_NODES", "redis://sentinel-2:port", "invalid port"),
    ],
)
def test_rejects_invalid_sentinel_settings(sentinel_env, variable, value, message):
    monkeypatch, _ = sentinel_env
    monkeypatch.setenv(variable, value)

    with pytest.raises(ValueError, match=message) as error:
        redis_utils._create_redis_client_from_env(decode_responses=False)

    assert "secret" not in str(error.value)
