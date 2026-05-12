import atexit
import logging
import os
import threading
import time
import typing
from typing import TYPE_CHECKING

from .base_prompt import BasePrompt

if TYPE_CHECKING:
    pass

logger = logging.getLogger(__name__)

DEFAULT_TTL_SECONDS = 300
_MIN_REFRESH_INTERVAL_SECONDS = 1.0


def _int_env(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


# Resolved once at import time. Override via OPIK_PROMPT_CACHE_TTL_SECONDS (integer seconds, >= 1).
_PROMPT_CACHE_TTL_SECONDS: int = _int_env(
    "OPIK_PROMPT_CACHE_TTL_SECONDS", DEFAULT_TTL_SECONDS
)

_CacheKey = typing.Tuple[str, typing.Optional[str], typing.Optional[str]]


class PromptCacheEntry:
    def __init__(
        self,
        prompt: BasePrompt,
        pinned: bool,
        ttl_seconds: int = DEFAULT_TTL_SECONDS,
    ) -> None:
        self._lock = threading.RLock()
        self._prompt = prompt
        self._pinned = pinned
        self._ttl_seconds = ttl_seconds
        self._last_fetch = time.monotonic()
        self._refresh_callback: typing.Optional[
            typing.Callable[[], typing.Optional[BasePrompt]]
        ] = None

    @property
    def prompt(self) -> BasePrompt:
        with self._lock:
            return self._prompt

    def set_refresh_callback(
        self, callback: typing.Callable[[], typing.Optional[BasePrompt]]
    ) -> None:
        with self._lock:
            if self._refresh_callback is None:
                self._refresh_callback = callback

    def update(self, prompt: BasePrompt) -> None:
        with self._lock:
            self._prompt = prompt
            self._last_fetch = time.monotonic()

    def is_stale(self) -> bool:
        if self._pinned:
            return False
        with self._lock:
            return (time.monotonic() - self._last_fetch) >= self._ttl_seconds

    def try_background_refresh(self) -> None:
        with self._lock:
            callback = self._refresh_callback
        if callback is None:
            return
        try:
            new_prompt = callback()
            if new_prompt is not None:
                self.update(new_prompt)
        except Exception:
            logger.debug("Background prompt cache refresh failed", exc_info=True)


class PromptCacheRefreshThread(threading.Thread):
    def __init__(
        self,
        get_entries: typing.Callable[[], typing.List[PromptCacheEntry]],
        interval_seconds: typing.Optional[float] = None,
    ) -> None:
        super().__init__(daemon=True, name="OpikPromptCacheRefresh")
        self._get_entries = get_entries
        self._stop_event = threading.Event()
        self._interval = interval_seconds

    def run(self) -> None:
        while not self._stop_event.is_set():
            self._refresh_all_stale()
            interval = self._interval or float(_PROMPT_CACHE_TTL_SECONDS)
            self._stop_event.wait(max(interval, _MIN_REFRESH_INTERVAL_SECONDS))

    def _refresh_all_stale(self) -> None:
        for entry in self._get_entries():
            if self._stop_event.is_set():
                break
            if entry.is_stale():
                entry.try_background_refresh()

    def close(self) -> None:
        self._stop_event.set()


class PromptCacheRegistry:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._entries: typing.Dict[_CacheKey, PromptCacheEntry] = {}
        self._thread: typing.Optional[PromptCacheRefreshThread] = None
        self._thread_lock = threading.Lock()

    def get(self, key: _CacheKey) -> typing.Optional[PromptCacheEntry]:
        with self._lock:
            return self._entries.get(key)

    def set(self, key: _CacheKey, entry: PromptCacheEntry) -> None:
        with self._lock:
            self._entries[key] = entry

    def get_entries(self) -> typing.List[PromptCacheEntry]:
        with self._lock:
            return list(self._entries.values())

    def clear(self) -> None:
        self.stop_refresh_thread()
        with self._lock:
            self._entries.clear()

    def ensure_refresh_thread_started(self) -> None:
        with self._thread_lock:
            if self._thread is not None and self._thread.is_alive():
                return
            self._thread = PromptCacheRefreshThread(get_entries=self.get_entries)
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
                            "Prompt cache refresh thread did not stop within the timeout."
                        )
                except Exception:
                    logger.exception(
                        "Unexpected error while waiting for prompt cache refresh thread to stop."
                    )
                self._thread = None


_registry = PromptCacheRegistry()


def get_global_registry() -> PromptCacheRegistry:
    return _registry


_PromptT = typing.TypeVar("_PromptT", bound=BasePrompt)


def get_or_fetch(
    name: str,
    commit: typing.Optional[str],
    project_name: typing.Optional[str],
    fetch_fn: typing.Callable[[], typing.Optional[_PromptT]],
) -> typing.Optional[_PromptT]:
    """Return a cached prompt or fetch, cache, and return it.

    If the prompt is already cached, returns it immediately.
    Otherwise calls *fetch_fn* to produce one. If *fetch_fn* returns None
    (prompt not found), returns None without caching.

    For unpinned prompts (commit is None), *fetch_fn* is also registered as
    the background-refresh callback so the cache stays fresh.
    """
    key: _CacheKey = (name, commit, project_name)
    entry = _registry.get(key)
    if entry is not None:
        return typing.cast(_PromptT, entry.prompt)

    prompt = fetch_fn()
    if prompt is None:
        return None

    pinned = commit is not None
    entry = PromptCacheEntry(
        prompt=prompt, pinned=pinned, ttl_seconds=_PROMPT_CACHE_TTL_SECONDS
    )
    _registry.set(key, entry)

    if not pinned:
        entry.set_refresh_callback(fetch_fn)
        _registry.ensure_refresh_thread_started()

    return prompt
