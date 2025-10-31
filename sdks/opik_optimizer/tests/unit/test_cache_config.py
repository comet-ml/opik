from __future__ import annotations

import os
from pathlib import Path

import litellm
import pytest

os.environ.setdefault("LITELLM_CACHE_TYPE", "memory")

from opik_optimizer import cache_config


def _reset_cache() -> None:
    try:
        cache_config.clear_cache()
    except Exception:
        pass
    litellm.cache = None  # type: ignore[assignment]


def test_initialize_cache_memory_type(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("LITELLM_CACHE_TYPE", "memory")
    monkeypatch.delenv("LITELLM_CACHE_TTL", raising=False)
    monkeypatch.delenv("LITELLM_CACHE_NAMESPACE", raising=False)

    cache = cache_config.initialize_cache()
    assert cache.type == "memory"
    _reset_cache()


def test_initialize_cache_disk_custom_dir(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    custom_dir = tmp_path / "llm_cache"
    monkeypatch.setenv("LITELLM_CACHE_TYPE", "disk")
    monkeypatch.setenv("LITELLM_CACHE_DIR", str(custom_dir))

    cache = cache_config.initialize_cache()
    assert cache.type == "disk"
    assert custom_dir.exists()
    _reset_cache()


def test_initialize_cache_applies_optional_settings(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("LITELLM_CACHE_TYPE", "memory")
    monkeypatch.setenv("LITELLM_CACHE_TTL", "30")

    cache = cache_config.initialize_cache()
    assert cache.type == "memory"
    assert cache.ttl == 30.0
    _reset_cache()


def test_initialize_cache_applies_namespace_for_disk(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    custom_dir = tmp_path / "disk"
    monkeypatch.setenv("LITELLM_CACHE_TYPE", "disk")
    monkeypatch.setenv("LITELLM_CACHE_DIR", str(custom_dir))
    monkeypatch.setenv("LITELLM_CACHE_NAMESPACE", "test-namespace")

    cache = cache_config.initialize_cache()
    assert cache.type == "disk"
    assert cache.namespace == "test-namespace"
    _reset_cache()
