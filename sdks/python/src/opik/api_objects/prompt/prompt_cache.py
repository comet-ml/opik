import atexit
import collections
import logging
import os
import threading
import time
import typing

from .base_prompt import BasePrompt

logger = logging.getLogger(__name__)

DEFAULT_TTL_SECONDS = 300
_MIN_REFRESH_INTERVAL_SECONDS = 1.0


def _int_env(name: str, default: int, minimum: int = 1) -> int:
    raw = os.environ.get(name)
    if raw is None:
        return default
    try:
        value = int(raw)
    except ValueError:
        return default
    return max(value, minimum)


# Resolved once at import time. Override via OPIK_PROMPT_CACHE_TTL_SECONDS (integer seconds, >= 1).
_PROMPT_CACHE_TTL_SECONDS: int = _int_env(
    "OPIK_PROMPT_CACHE_TTL_SECONDS", DEFAULT_TTL_SECONDS
)

_CacheKey = typing.Tuple[str, typing.Optional[str], typing.Optional[str], str]

_RefreshCallback = typing.Callable[[], typing.Optional[BasePrompt]]

_MAX_CACHE_SIZE = 128


class _CachedPrompt:
    __slots__ = ("prompt", "pinned", "ttl_seconds", "last_fetch", "refresh_callback")

    def __init__(
        self,
        prompt: BasePrompt,
        pinned: bool,
        ttl_seconds: int,
        refresh_callback: typing.Optional[_RefreshCallback] = None,
    ) -> None:
        self.prompt = prompt
        self.pinned = pinned
        self.ttl_seconds = ttl_seconds
        self.last_fetch = time.monotonic()
        self.refresh_callback = refresh_callback

    def is_stale(self) -> bool:
        if self.pinned:
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

    def get_or_fetch(
        self,
        key: _CacheKey,
        fetch_fn: _RefreshCallback,
        pinned: bool,
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
            pinned=pinned,
            ttl_seconds=_PROMPT_CACHE_TTL_SECONDS,
            refresh_callback=None if pinned else fetch_fn,
        )
        with self._lock:
            self._entries[key] = cached
            self._entries.move_to_end(key)
            while len(self._entries) > self._max_size:
                self._entries.popitem(last=False)

        if not pinned:
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
            interval = max(
                float(_PROMPT_CACHE_TTL_SECONDS), _MIN_REFRESH_INTERVAL_SECONDS
            )
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
                logger.debug("Background prompt cache refresh failed", exc_info=True)

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
) -> typing.Optional[_PromptT]:
    """Return a cached prompt or fetch, cache, and return it.

    If the prompt is already cached, returns it immediately.
    Otherwise calls *fetch_fn* to produce one. If *fetch_fn* returns None
    (prompt not found), returns None without caching.

    For unpinned prompts (commit is None), *fetch_fn* is also registered as
    the background-refresh callback so the cache stays fresh.
    """
    key: _CacheKey = (name, commit, project_name, template_structure)
    result = _cache.get_or_fetch(
        key=key,
        fetch_fn=fetch_fn,
        pinned=commit is not None,
    )
    return typing.cast(typing.Optional[_PromptT], result)
