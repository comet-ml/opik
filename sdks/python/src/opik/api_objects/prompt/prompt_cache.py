import atexit
import collections
import logging
import threading
import time
import typing

from opik import config as opik_config
from .base_prompt import BasePrompt

logger = logging.getLogger(__name__)

_MIN_REFRESH_INTERVAL_SECONDS = 1.0

_CacheKey = typing.Tuple[
    str,
    typing.Optional[str],
    typing.Optional[str],
    str,
    typing.Optional[str],
    typing.Optional[str],
]
#             name  pin(commit/ver)  project_name  tmpl  environment  mask_id

_RefreshCallback = typing.Callable[[], typing.Optional[BasePrompt]]

_MAX_CACHE_SIZE = 128


class _CachedPrompt:
    __slots__ = ("prompt", "ttl_seconds", "last_fetch", "refresh_callback")

    def __init__(
        self,
        prompt: BasePrompt,
        ttl_seconds: typing.Optional[int],
        refresh_callback: typing.Optional[_RefreshCallback] = None,
    ) -> None:
        self.prompt = prompt
        self.ttl_seconds = ttl_seconds
        self.last_fetch = time.monotonic()
        self.refresh_callback = refresh_callback

    def is_stale(self) -> bool:
        if self.ttl_seconds is None:
            return False
        return (time.monotonic() - self.last_fetch) >= self.ttl_seconds


class PromptCache:
    def __init__(self, max_size: int = _MAX_CACHE_SIZE) -> None:
        self._lock = threading.Lock()
        self._entries: collections.OrderedDict[_CacheKey, _CachedPrompt] = (
            collections.OrderedDict()
        )
        self._max_size = max_size
        self._thread: typing.Optional[threading.Thread] = None
        self._stop_event = threading.Event()

    def get(self, key: _CacheKey) -> typing.Optional[BasePrompt]:
        with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                return None
            self._entries.move_to_end(key)
            return entry.prompt

    def clear(self) -> None:
        self._stop_refresh_thread()
        with self._lock:
            self._entries.clear()

    def invalidate_for_prompt(
        self, name: str, project_name: typing.Optional[str]
    ) -> None:
        """Drop every cached entry for the given prompt name + project scope.

        Used after operations that change the env-to-version mapping (such as
        ``Opik.set_prompt_environment``) so that subsequent
        ``get_prompt(..., environment=...)`` calls cannot return a stale version.
        """
        with self._lock:
            stale_keys = [
                key
                for key in self._entries
                if key[0] == name and key[2] == project_name
            ]
            for key in stale_keys:
                del self._entries[key]

    def get_or_fetch(
        self,
        key: _CacheKey,
        fetch_fn: _RefreshCallback,
        ttl_seconds: typing.Optional[int],
        refresh_callback: typing.Optional[_RefreshCallback] = None,
    ) -> typing.Optional[BasePrompt]:
        with self._lock:
            entry = self._entries.get(key)
            if entry is not None:
                self._entries.move_to_end(key)
                return entry.prompt

        prompt = fetch_fn()
        if prompt is None:
            return None

        cached = _CachedPrompt(
            prompt=prompt,
            ttl_seconds=ttl_seconds,
            refresh_callback=refresh_callback,
        )
        with self._lock:
            self._entries[key] = cached
            self._entries.move_to_end(key)
            while len(self._entries) > self._max_size:
                self._entries.popitem(last=False)

        if ttl_seconds is not None:
            self._ensure_refresh_thread_started()

        return prompt

    def _ensure_refresh_thread_started(self) -> None:
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return
            self._stop_event.clear()
            self._thread = threading.Thread(
                target=self._refresh_loop, daemon=True, name="OpikPromptCacheRefresh"
            )
            self._thread.start()
            atexit.register(self._stop_refresh_thread)

    def _refresh_loop(self) -> None:
        while not self._stop_event.is_set():
            self._refresh_stale_entries()
            ttl = opik_config.OpikConfig().prompt_cache_ttl_seconds
            interval = max(float(ttl), _MIN_REFRESH_INTERVAL_SECONDS)
            self._stop_event.wait(interval)

    def _refresh_stale_entries(self) -> None:
        with self._lock:
            entries = list(self._entries.values())
        for entry in entries:
            if self._stop_event.is_set():
                break
            if not entry.is_stale() or entry.refresh_callback is None:
                continue
            try:
                new_prompt = entry.refresh_callback()
                if new_prompt is not None:
                    with self._lock:
                        entry.prompt = new_prompt
                        entry.last_fetch = time.monotonic()
            except Exception:
                logger.debug(
                    "Background prompt cache refresh failed for prompt %r",
                    entry.prompt.name,
                    exc_info=True,
                )

    def _stop_refresh_thread(self) -> None:
        with self._lock:
            if self._thread is None:
                return
            thread = self._thread
            self._stop_event.set()
            self._thread = None
        try:
            thread.join(timeout=5)
            if thread.is_alive():
                logger.error(
                    "Prompt cache refresh thread did not stop within the timeout."
                )
        except Exception:
            logger.exception(
                "Unexpected error while waiting for prompt cache refresh thread to stop."
            )


_cache = PromptCache()


def get_global_cache() -> PromptCache:
    return _cache


_PromptT = typing.TypeVar("_PromptT", bound=BasePrompt)


def get_or_fetch(
    name: str,
    commit: typing.Optional[str],
    project_name: typing.Optional[str],
    template_structure: str,
    fetch_fn: typing.Callable[[], typing.Optional[_PromptT]],
    mask_id: typing.Optional[str] = None,
    version: typing.Optional[str] = None,
    environment: typing.Optional[str] = None,
) -> typing.Optional[_PromptT]:
    """Return a cached prompt or fetch, cache, and return it.

    If the prompt is already cached, returns it immediately.
    Otherwise calls *fetch_fn* to produce one. If *fetch_fn* returns None
    (prompt not found), returns None without caching.

    ``commit`` and ``version`` reuse the same cache key slot (commits are 8
    hex chars, versions match ``v\\d+``, so they cannot collide). Only
    ``commit`` pins indefinitely — a sequential ``version`` like ``"v3"``
    can be reassigned by the backend if the underlying version is deleted
    and recreated, so it is subject to the normal TTL refresh just like
    "latest" lookups.

    For masked prompts (mask_id is not None), the entry is TTL-evicted like
    normal unpinned entries but has no background refresh callback.
    """
    identifier = version if version is not None else commit
    ttl = opik_config.OpikConfig().prompt_cache_ttl_seconds
    ttl_seconds = None if commit is not None else ttl
    refresh_callback = (
        fetch_fn if (ttl_seconds is not None and mask_id is None) else None
    )
    key: _CacheKey = (
        name,
        identifier,
        project_name,
        template_structure,
        environment,
        mask_id,
    )
    result = _cache.get_or_fetch(
        key=key,
        fetch_fn=fetch_fn,
        ttl_seconds=ttl_seconds,
        refresh_callback=refresh_callback,
    )
    return typing.cast(typing.Optional[_PromptT], result)


def invalidate_for_prompt(name: str, project_name: typing.Optional[str]) -> None:
    """Drop every cached entry for the given prompt name + project scope."""
    _cache.invalidate_for_prompt(name=name, project_name=project_name)
