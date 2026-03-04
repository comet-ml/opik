import atexit
import logging
import os
import threading
import time
import typing

from .blueprint import Blueprint

logger = logging.getLogger(__name__)

DEFAULT_TTL_SECONDS = 300
_MIN_REFRESH_INTERVAL_SECONDS = 1.0

_CacheKey = typing.Tuple[str, typing.Optional[str], typing.Optional[str]]


def _get_ttl_seconds() -> int:
    raw = os.environ.get("OPIK_CONFIG_TTL_SECONDS")
    if raw is not None:
        try:
            return int(raw)
        except ValueError:
            pass
    return DEFAULT_TTL_SECONDS


class SharedConfigCache:
    def __init__(self, ttl_seconds: int = DEFAULT_TTL_SECONDS) -> None:
        self._lock = threading.RLock()
        self.blueprint_id: typing.Optional[str] = None
        self.values: typing.Dict[str, typing.Any] = {}
        self._registered_field_types: typing.Dict[str, typing.Any] = {}
        self._ttl_seconds = ttl_seconds
        self._last_fetch: typing.Optional[float] = None
        self._refresh_callback: typing.Optional[
            typing.Callable[[], typing.Optional[Blueprint]]
        ] = None

    def set_refresh_callback(
        self, callback: typing.Callable[[], typing.Optional[Blueprint]]
    ) -> None:
        with self._lock:
            if self._refresh_callback is None:
                self._refresh_callback = callback

    def register_fields(
        self, prefixed_field_types: typing.Dict[str, typing.Any]
    ) -> None:
        with self._lock:
            self._registered_field_types.update(prefixed_field_types)

    @property
    def all_field_types(self) -> typing.Dict[str, typing.Any]:
        with self._lock:
            return dict(self._registered_field_types)

    def update(self, blueprint: Blueprint) -> None:
        new_values = dict(blueprint._values)
        with self._lock:
            self.blueprint_id = blueprint.id
            self.values = new_values
            self._last_fetch = time.monotonic()

    def value_keys(self) -> typing.Set[str]:
        with self._lock:
            return set(self.values.keys())

    def is_stale(self) -> bool:
        with self._lock:
            if self._last_fetch is None:
                return True
            return (time.monotonic() - self._last_fetch) >= self._ttl_seconds

    def try_background_refresh(self) -> None:
        with self._lock:
            callback = self._refresh_callback
        if callback is None:
            return
        try:
            bp = callback()
            if bp is not None:
                self.update(bp)
        except Exception:
            logger.debug("Background cache refresh failed", exc_info=True)


class CacheRefreshThread(threading.Thread):
    def __init__(
        self,
        get_caches: typing.Callable[[], typing.List[SharedConfigCache]],
        interval_seconds: typing.Optional[float] = None,
    ) -> None:
        super().__init__(daemon=True, name="OpikCacheRefresh")
        self._get_caches = get_caches
        self._stop_event = threading.Event()
        self._interval = interval_seconds

    def run(self) -> None:
        while not self._stop_event.is_set():
            self._refresh_all_stale()
            interval = self._interval or float(_get_ttl_seconds())
            self._stop_event.wait(max(interval, _MIN_REFRESH_INTERVAL_SECONDS))

    def _refresh_all_stale(self) -> None:
        for cache in self._get_caches():
            if self._stop_event.is_set():
                break
            if cache.is_stale():
                cache.try_background_refresh()

    def close(self) -> None:
        self._stop_event.set()


class SharedCacheRegistry:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._caches: typing.Dict[_CacheKey, SharedConfigCache] = {}
        self._thread: typing.Optional[CacheRefreshThread] = None
        self._thread_lock = threading.Lock()

    def get(
        self,
        project_name: str,
        env: typing.Optional[str],
        mask_id: typing.Optional[str],
    ) -> SharedConfigCache:
        key: _CacheKey = (project_name, env, mask_id)
        with self._lock:
            if key not in self._caches:
                self._caches[key] = SharedConfigCache(ttl_seconds=_get_ttl_seconds())
            return self._caches[key]

    def clear(self) -> None:
        self.stop_refresh_thread()
        with self._lock:
            self._caches.clear()

    def ensure_refresh_thread_started(self) -> None:
        with self._thread_lock:
            if self._thread is not None and self._thread.is_alive():
                return
            self._thread = CacheRefreshThread(
                get_caches=lambda: list(self._caches.values())
            )
            self._thread.start()
            atexit.register(self.stop_refresh_thread)

    def stop_refresh_thread(self) -> None:
        with self._thread_lock:
            if self._thread is not None:
                thread = self._thread
                thread.close()
                try:
                    thread.join(timeout=5)
                    if thread.is_alive():
                        logger.error(
                            "Cache refresh thread did not stop within the timeout."
                        )
                except Exception:
                    logger.exception(
                        "Unexpected error while waiting for cache refresh thread to stop."
                    )
                self._thread = None


_registry = SharedCacheRegistry()
