import os
import time
import typing

from .blueprint import Blueprint

DEFAULT_TTL_SECONDS = 300

_CacheKey = typing.Tuple[str, typing.Optional[str], typing.Optional[str]]

_shared_cache_registry: typing.Dict[_CacheKey, "SharedConfigCache"] = {}


def _get_ttl_seconds() -> int:
    raw = os.environ.get("OPIK_CONFIG_TTL_SECONDS")
    if raw is not None:
        try:
            return int(raw)
        except ValueError:
            pass
    return DEFAULT_TTL_SECONDS


def get_shared_cache(
    project_name: str,
    env: typing.Optional[str],
    mask_id: typing.Optional[str],
) -> "SharedConfigCache":
    key: _CacheKey = (project_name, env, mask_id)
    if key not in _shared_cache_registry:
        _shared_cache_registry[key] = SharedConfigCache(ttl_seconds=_get_ttl_seconds())
    return _shared_cache_registry[key]


def clear_shared_caches() -> None:
    _shared_cache_registry.clear()


class SharedConfigCache:
    def __init__(self, ttl_seconds: int = DEFAULT_TTL_SECONDS) -> None:
        self.blueprint_id: typing.Optional[str] = None
        self.values: typing.Dict[str, typing.Any] = {}
        self._registered_field_types: typing.Dict[str, typing.Any] = {}
        self._ttl_seconds = ttl_seconds
        self._last_fetch: typing.Optional[float] = None

    def register_fields(
        self, prefixed_field_types: typing.Dict[str, typing.Any]
    ) -> None:
        self._registered_field_types.update(prefixed_field_types)

    @property
    def all_field_types(self) -> typing.Dict[str, typing.Any]:
        return dict(self._registered_field_types)

    def apply(self, blueprint: Blueprint) -> None:
        self.blueprint_id = blueprint.id
        self.values = dict(blueprint._values)
        self._last_fetch = time.monotonic()

    def is_stale(self) -> bool:
        if self._last_fetch is None:
            return True
        return (time.monotonic() - self._last_fetch) >= self._ttl_seconds
