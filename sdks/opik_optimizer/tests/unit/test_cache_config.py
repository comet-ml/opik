from __future__ import annotations

import os
from pathlib import Path

import litellm
import pytest

os.environ.setdefault("OPIK_CACHE_TYPE", "memory")

from opik_optimizer import cache_config


def _reset_cache() -> None:
    cache_config.enable_cache()
    try:
        cache_config.clear_cache()
    except AttributeError:
        pass
    litellm.cache = None  # type: ignore[assignment]


def test_initialize_cache_memory_type(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("OPIK_CACHE_TYPE", "memory")
    monkeypatch.delenv("OPIK_CACHE_TTL", raising=False)
    monkeypatch.delenv("OPIK_CACHE_NAMESPACE", raising=False)
    monkeypatch.delenv("LITELLM_CACHE_TTL", raising=False)
    monkeypatch.delenv("LITELLM_CACHE_NAMESPACE", raising=False)

    cache = cache_config.initialize_cache()
    assert cache is not None
    assert cache.type == "memory"
    _reset_cache()


def test_initialize_cache_disk_custom_dir(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    custom_dir = tmp_path / "llm_cache"
    monkeypatch.setenv("OPIK_CACHE_TYPE", "disk")
    monkeypatch.setenv("OPIK_CACHE_DIR", str(custom_dir))

    cache = cache_config.initialize_cache()
    assert cache is not None
    assert cache.type == "disk"
    assert custom_dir.exists()
    _reset_cache()


def test_initialize_cache_applies_optional_settings(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("OPIK_CACHE_TYPE", "memory")
    monkeypatch.setenv("OPIK_CACHE_TTL", "30")

    cache = cache_config.initialize_cache()
    assert cache is not None
    assert cache.type == "memory"
    assert cache.ttl == 30.0
    _reset_cache()


def test_initialize_cache_applies_namespace_for_disk(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    custom_dir = tmp_path / "disk"
    monkeypatch.setenv("OPIK_CACHE_TYPE", "disk")
    monkeypatch.setenv("OPIK_CACHE_DIR", str(custom_dir))
    monkeypatch.setenv("OPIK_CACHE_NAMESPACE", "test-namespace")

    cache = cache_config.initialize_cache()
    assert cache is not None
    assert cache.type == "disk"
    assert cache.namespace == "test-namespace"
    _reset_cache()


def test_disable_cache_via_api(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("OPIK_CACHE_DISABLED", raising=False)
    cache_config.enable_cache()

    cache_config.disable_cache()
    cache = cache_config.initialize_cache()
    assert cache is None

    cache_config.enable_cache()


def test_disable_cache_via_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    cache_config.enable_cache()
    monkeypatch.setenv("OPIK_CACHE_DISABLED", "1")

    cache = cache_config.initialize_cache()
    assert cache is None

    cache_config.enable_cache()
