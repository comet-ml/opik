import json
import os
from pathlib import Path
from typing import Any

import litellm
from litellm.caching import Cache

_ALLOWED_CACHE_TYPES = {"disk", "memory"}
_ENV_PREFIX = "OPIK_CACHE_"
_DISABLED_ENV_KEY = f"{_ENV_PREFIX}DISABLED"
_CACHE_DISABLED = False


def _get_env(name: str, default: str | None = None) -> str | None:
    return os.environ.get(f"{_ENV_PREFIX}{name}", default)


_CACHE_DISABLED = ((_get_env("DISABLED", "") or "").strip().lower()) in {
    "1",
    "true",
    "yes",
    "on",
}


def get_cache_directory() -> str:
    """Return the expanded path for any disk-backed LiteLLM cache."""
    directory = _get_env("DIR")
    if not directory:
        directory = "~/.litellm_cache"
    return os.path.expanduser(directory)


def _load_additional_cache_config() -> dict[str, Any]:
    """Return optional JSON configuration specified via `LITELLM_CACHE_CONFIG_JSON`."""

    raw = _get_env("CONFIG_JSON")
    if not raw:
        return {}
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return data if isinstance(data, dict) else {}


def _resolve_cache_type(extra_config: dict[str, Any]) -> str:
    """Determine the cache type using environment overrides and optional JSON config."""

    env_value = _get_env("TYPE")
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
    raw_value = _get_env("TTL")
    if raw_value is None:
        return None
    try:
        parsed = float(raw_value)
    except ValueError:
        return None
    return parsed if parsed > 0 else None


def _resolve_cache_namespace() -> str | None:
    namespace = _get_env("NAMESPACE")
    return namespace or None


def _build_cache_config() -> dict[str, Any]:
    extra_config = _load_additional_cache_config()
    cache_type = _resolve_cache_type(extra_config)
    cache_ttl = _resolve_cache_ttl()
    cache_namespace = _resolve_cache_namespace()

    if cache_type == "memory":
        memory_config: dict[str, Any] = {"type": "memory"}
        if cache_ttl is not None:
            memory_config["ttl"] = cache_ttl
        memory_config.update(
            {
                k: v
                for k, v in extra_config.items()
                if k not in {"type", "disk_cache_dir"}
            }
        )
        return memory_config

    if cache_type == "disk":
        cache_dir = get_cache_directory()
        Path(cache_dir).mkdir(parents=True, exist_ok=True)
        disk_config: dict[str, Any] = {"type": "disk", "disk_cache_dir": cache_dir}
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


def initialize_cache() -> Cache | None:
    """Configure LiteLLM caching using repo defaults and environment overrides."""
    global _CACHE_DISABLED

    disable_env = ((_get_env("DISABLED", "") or "").strip().lower()) in {
        "1",
        "true",
        "yes",
        "on",
    }
    if disable_env:
        _CACHE_DISABLED = True

    if _CACHE_DISABLED:
        litellm.cache = None
        return None

    cache_config = _build_cache_config()
    litellm.cache = Cache(**cache_config)
    return litellm.cache


def disable_cache(*, persist: bool = False) -> None:
    """
    Disable LiteLLM caching for the current process.

    Args:
        persist: When True, set `LITELLM_CACHE_DISABLED=1` so child processes inherit the setting.
    """
    global _CACHE_DISABLED
    _CACHE_DISABLED = True
    if persist:
        os.environ[_DISABLED_ENV_KEY] = "1"
    litellm.cache = None


def enable_cache() -> None:
    """Re-enable LiteLLM caching for subsequent optimizer instantiations."""
    global _CACHE_DISABLED
    _CACHE_DISABLED = False
    os.environ.pop(_DISABLED_ENV_KEY, None)


def clear_cache() -> None:
    """Clear the LiteLLM cache if one is configured."""
    cache_obj = getattr(litellm, "cache", None)
    if cache_obj:
        cache_obj.clear()


def initialize_optimizer_cache() -> Cache | None:
    """
    Backwards-compatible alias for `initialize_cache`.

    Returns:
        The initialized LiteLLM cache instance or None when caching is disabled.
    """
    return initialize_cache()
