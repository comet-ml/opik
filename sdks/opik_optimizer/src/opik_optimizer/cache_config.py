import json
import os
from pathlib import Path

import litellm
from litellm.caching import Cache

_ALLOWED_CACHE_TYPES = {"disk", "memory"}


def get_cache_directory() -> str:
    """Return the expanded path for any disk-backed LiteLLM cache."""
    return os.path.expanduser(os.environ.get("LITELLM_CACHE_DIR", "~/.litellm_cache"))


def _load_additional_cache_config() -> dict[str, object]:
    """Return optional JSON configuration specified via `LITELLM_CACHE_CONFIG_JSON`."""

    raw = os.environ.get("LITELLM_CACHE_CONFIG_JSON")
    if not raw:
        return {}
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return data if isinstance(data, dict) else {}


def _resolve_cache_type(extra_config: dict[str, object]) -> str:
    """Determine the cache type using environment overrides and optional JSON config."""

    env_value = os.environ.get("LITELLM_CACHE_TYPE")
    if env_value:
        cache_type = env_value.strip().lower()
    else:
        raw = extra_config.get("type", "disk")
        cache_type = str(raw).lower()

    if cache_type not in _ALLOWED_CACHE_TYPES:
        # Allow LiteLLM to decide for advanced backends while still supporting legacy defaults.
        return cache_type
    return cache_type


def _resolve_cache_ttl() -> float | None:
    raw_value = os.environ.get("LITELLM_CACHE_TTL")
    if raw_value is None:
        return None
    try:
        parsed = float(raw_value)
    except ValueError:
        return None
    return parsed if parsed > 0 else None


def _resolve_cache_namespace() -> str | None:
    namespace = os.environ.get("LITELLM_CACHE_NAMESPACE")
    return namespace or None


def _build_cache_config() -> dict[str, object]:
    extra_config = _load_additional_cache_config()
    cache_type = _resolve_cache_type(extra_config)
    cache_ttl = _resolve_cache_ttl()
    cache_namespace = _resolve_cache_namespace()

    if cache_type == "memory":
        config: dict[str, object] = {"type": "memory"}
        if cache_ttl is not None:
            config["ttl"] = cache_ttl
        config.update(
            {
                k: v
                for k, v in extra_config.items()
                if k not in {"type", "disk_cache_dir"}
            }
        )
        return config

    if cache_type == "disk":
        cache_dir = get_cache_directory()
        Path(cache_dir).mkdir(parents=True, exist_ok=True)
        disk_config: dict[str, object] = {"type": "disk", "disk_cache_dir": cache_dir}
        if cache_ttl is not None:
            disk_config["ttl"] = cache_ttl
        if cache_namespace is not None:
            disk_config["namespace"] = cache_namespace
        disk_config.update(
            {
                k: v
                for k, v in extra_config.items()
                if k not in {"type", "disk_cache_dir"}
            }
        )
        return disk_config

    other_config = dict(extra_config)
    other_config["type"] = cache_type
    if cache_ttl is not None and "ttl" not in other_config:
        other_config["ttl"] = cache_ttl
    if cache_namespace is not None and "namespace" not in other_config:
        other_config["namespace"] = cache_namespace
    return other_config


def initialize_cache() -> Cache:
    """Configure LiteLLM caching using repo defaults and environment overrides."""
    cache_config = _build_cache_config()
    litellm.cache = Cache(**cache_config)
    return litellm.cache


def clear_cache() -> None:
    """Clear the LiteLLM cache if one is configured."""
    if litellm.cache:
        litellm.cache.clear()
