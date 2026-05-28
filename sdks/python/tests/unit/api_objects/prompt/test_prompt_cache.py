"""Unit tests for the prompt client-side cache."""

import time
from typing import Optional
from unittest import mock

import pytest

from opik import config as opik_config
from opik.api_objects.prompt import prompt_cache
from opik.api_objects.prompt.prompt_cache import (
    PromptCache,
    get_global_cache,
)


def _get_ttl() -> int:
    return opik_config.OpikConfig().prompt_cache_ttl_seconds


@pytest.fixture(autouse=True)
def clear_caches_after_test():
    yield
    get_global_cache().clear()


@pytest.fixture
def cache():
    c = PromptCache()
    yield c
    c.clear()


def _make_mock_prompt(
    name: str = "my-prompt",
    commit: str = "abc123",
    prompt_id: Optional[str] = None,
) -> mock.Mock:
    p = mock.Mock()
    p.name = name
    p.commit = commit
    p.__internal_api__prompt_id__ = prompt_id
    return p


class TestPromptCache:
    def test_get__missing_key__returns_none(self, cache):
        assert cache.get(("p", None, None, "text")) is None

    def test_get_or_fetch__cache_miss__fetches_and_caches(self, cache):
        p = _make_mock_prompt()
        fetch_fn = mock.Mock(return_value=p)
        result = cache.get_or_fetch(
            ("p", None, None, "text"), fetch_fn, ttl_seconds=300
        )
        assert result is p
        fetch_fn.assert_called_once()

    def test_get_or_fetch__cache_hit__does_not_call_fetch_fn(self, cache):
        p = _make_mock_prompt()
        fetch_fn = mock.Mock(return_value=p)
        cache.get_or_fetch(("p", None, None, "text"), fetch_fn, ttl_seconds=300)
        fetch_fn.reset_mock()
        result = cache.get_or_fetch(
            ("p", None, None, "text"), fetch_fn, ttl_seconds=300
        )
        assert result is p
        fetch_fn.assert_not_called()

    def test_get_or_fetch__fetch_returns_none__returns_none(self, cache):
        fetch_fn = mock.Mock(return_value=None)
        result = cache.get_or_fetch(
            ("missing", None, None, "text"), fetch_fn, ttl_seconds=300
        )
        assert result is None

    def test_clear__populated_cache__removes_all_entries(self, cache):
        p = _make_mock_prompt()
        cache.get_or_fetch(
            ("p", None, None, "text"), mock.Mock(return_value=p), ttl_seconds=None
        )
        cache.clear()
        assert cache.get(("p", None, None, "text")) is None

    def test_clear__running_refresh_thread__stops_thread(self, cache):
        p = _make_mock_prompt()
        cache.get_or_fetch(
            ("p", None, None, "text"), mock.Mock(return_value=p), ttl_seconds=300
        )
        assert cache._thread is not None and cache._thread.is_alive()
        cache.clear()
        assert cache._thread is None

    def test_refresh_thread__unpinned_entry__starts_thread(self, cache):
        p = _make_mock_prompt()
        cache.get_or_fetch(
            ("p", None, None, "text"), mock.Mock(return_value=p), ttl_seconds=300
        )
        assert cache._thread is not None
        assert cache._thread.is_alive()

    def test_refresh_thread__pinned_entry__does_not_start_thread(self, cache):
        p = _make_mock_prompt()
        cache.get_or_fetch(
            ("p", None, None, "text"), mock.Mock(return_value=p), ttl_seconds=None
        )
        assert cache._thread is None

    def test_get_or_fetch__different_keys__returns_separate_entries(self, cache):
        p1 = _make_mock_prompt(commit="c1")
        p2 = _make_mock_prompt(commit="c2")
        cache.get_or_fetch(
            ("p", "c1", None, "text"), mock.Mock(return_value=p1), ttl_seconds=None
        )
        cache.get_or_fetch(
            ("p", "c2", None, "text"), mock.Mock(return_value=p2), ttl_seconds=None
        )
        assert cache.get(("p", "c1", None, "text")) is p1
        assert cache.get(("p", "c2", None, "text")) is p2


class TestBackgroundRefresh:
    def test_refresh__stale_entry__updates_prompt(self):
        new_prompt = _make_mock_prompt(commit=None)
        callback = mock.Mock(return_value=new_prompt)

        cache = get_global_cache()
        base = time.monotonic()
        with mock.patch("opik.api_objects.prompt.prompt_cache.time") as mock_time:
            mock_time.monotonic.return_value = base
            prompt_cache.get_or_fetch("p", None, None, "text", callback)
            callback.reset_mock()
            callback.return_value = new_prompt

            mock_time.monotonic.return_value = base + _get_ttl() + 1
            mock_time.sleep = mock.Mock()
            cache._refresh_stale_entries()

            assert callback.call_count >= 1
            assert cache.get(("p", None, None, "text", None)) is new_prompt

    def test_refresh__non_stale_entry__skips_callback(self):
        p = _make_mock_prompt(commit=None)
        callback = mock.Mock(return_value=p)

        cache = get_global_cache()
        base = time.monotonic()
        with mock.patch("opik.api_objects.prompt.prompt_cache.time") as mock_time:
            mock_time.monotonic.return_value = base
            prompt_cache.get_or_fetch("p", None, None, "text", callback)
            callback.reset_mock()

            mock_time.monotonic.return_value = base + 0.2
            cache._refresh_stale_entries()

            callback.assert_not_called()

    def test_refresh__callback_raises__thread_survives(self):
        p = _make_mock_prompt(commit=None)
        callback = mock.Mock(side_effect=[p, RuntimeError("boom")])

        cache = get_global_cache()
        base = time.monotonic()
        with mock.patch("opik.api_objects.prompt.prompt_cache.time") as mock_time:
            mock_time.monotonic.return_value = base
            prompt_cache.get_or_fetch("p", None, None, "text", callback)

            mock_time.monotonic.return_value = base + _get_ttl() + 1
            cache._refresh_stale_entries()

            assert cache._thread.is_alive()


class TestGetOrFetch:
    def test_get_or_fetch__cache_miss__calls_fetch_fn(self):
        p = _make_mock_prompt()
        fetch_fn = mock.Mock(return_value=p)
        result = prompt_cache.get_or_fetch("p", None, None, "text", fetch_fn)
        assert result is p
        fetch_fn.assert_called_once()

    def test_get_or_fetch__fetch_returns_none__returns_none(self):
        fetch_fn = mock.Mock(return_value=None)
        result = prompt_cache.get_or_fetch("missing", None, None, "text", fetch_fn)
        assert result is None

    def test_get_or_fetch__cache_hit__does_not_call_fetch_fn(self):
        p = _make_mock_prompt()
        fetch_fn = mock.Mock(return_value=p)
        prompt_cache.get_or_fetch("p", None, None, "text", fetch_fn)
        fetch_fn.reset_mock()

        result = prompt_cache.get_or_fetch("p", None, None, "text", fetch_fn)
        assert result is p
        fetch_fn.assert_not_called()

    def test_get_or_fetch__pinned_commit__no_refresh_thread(self):
        p = _make_mock_prompt(commit="abc")
        fetch_fn = mock.Mock(return_value=p)
        prompt_cache.get_or_fetch("p", "abc", None, "text", fetch_fn)
        cache = get_global_cache()
        assert cache._thread is None

    def test_get_or_fetch__unpinned_commit__starts_refresh_thread(self):
        p = _make_mock_prompt(commit=None)
        fetch_fn = mock.Mock(return_value=p)
        prompt_cache.get_or_fetch("p", None, None, "text", fetch_fn)
        cache = get_global_cache()
        assert cache._thread is not None
        assert cache._thread.is_alive()

    def test_get_or_fetch__cache_hit__returns_same_object(self):
        p = _make_mock_prompt()
        fetch_fn = mock.Mock(return_value=p)
        prompt_cache.get_or_fetch("p", None, "proj", "text", fetch_fn)
        first = prompt_cache.get_or_fetch("p", None, "proj", "text", fetch_fn)
        second = prompt_cache.get_or_fetch("p", None, "proj", "text", fetch_fn)
        assert first is second is p

    def test_get_or_fetch__different_keys__returns_separate_entries(self):
        p1 = _make_mock_prompt(commit="c1")
        p2 = _make_mock_prompt(commit="c2")
        prompt_cache.get_or_fetch("p", "c1", None, "text", mock.Mock(return_value=p1))
        prompt_cache.get_or_fetch("p", "c2", None, "text", mock.Mock(return_value=p2))
        r1 = prompt_cache.get_or_fetch("p", "c1", None, "text", mock.Mock())
        r2 = prompt_cache.get_or_fetch("p", "c2", None, "text", mock.Mock())
        assert r1 is p1
        assert r2 is p2


class TestGetOrFetchVersionSelector:
    """Tests for the ``version`` parameter on the module-level ``get_or_fetch``."""

    def test_get_or_fetch__different_versions__return_separate_entries(self):
        p1 = _make_mock_prompt(commit="commitA")
        p2 = _make_mock_prompt(commit="commitB")
        fetch1 = mock.Mock(return_value=p1)
        fetch2 = mock.Mock(return_value=p2)

        first = prompt_cache.get_or_fetch("p", None, None, "text", fetch1, version="v1")
        second = prompt_cache.get_or_fetch(
            "p", None, None, "text", fetch2, version="v2"
        )

        assert first is p1
        assert second is p2
        fetch1.assert_called_once()
        fetch2.assert_called_once()

    def test_get_or_fetch__same_version__second_call_hits_cache(self):
        p = _make_mock_prompt()
        fetch_fn = mock.Mock(return_value=p)

        first = prompt_cache.get_or_fetch(
            "p", None, None, "text", fetch_fn, version="v2"
        )
        second = prompt_cache.get_or_fetch(
            "p", None, None, "text", fetch_fn, version="v2"
        )

        assert first is second is p
        fetch_fn.assert_called_once()

    def test_get_or_fetch__commit_pin_and_version_pin__do_not_collide(self):
        p_commit = _make_mock_prompt(commit="abc12345")
        p_version = _make_mock_prompt(commit="def67890")
        fetch_commit = mock.Mock(return_value=p_commit)
        fetch_version = mock.Mock(return_value=p_version)

        from_commit = prompt_cache.get_or_fetch(
            "p", "abc12345", None, "text", fetch_commit
        )
        from_version = prompt_cache.get_or_fetch(
            "p", None, None, "text", fetch_version, version="v3"
        )

        assert from_commit is p_commit
        assert from_version is p_version
        fetch_commit.assert_called_once()
        fetch_version.assert_called_once()

    def test_get_or_fetch__version_selector__starts_refresh_thread(self):
        # Sequential versions can be reassigned by the backend if the underlying
        # version is deleted and recreated, so they must follow the normal TTL
        # refresh (not pinned indefinitely).
        p = _make_mock_prompt()
        fetch_fn = mock.Mock(return_value=p)
        prompt_cache.get_or_fetch("p", None, None, "text", fetch_fn, version="v1")
        cache = get_global_cache()
        assert cache._thread is not None
        assert cache._thread.is_alive()


class TestPromptCacheEdgeCases:
    def test_get_or_fetch__multiple_unpinned_inserts__reuses_single_refresh_thread(
        self, cache
    ):
        p = _make_mock_prompt()
        cache.get_or_fetch(
            ("a", None, None, "text"), mock.Mock(return_value=p), ttl_seconds=300
        )
        thread1 = cache._thread
        cache.get_or_fetch(
            ("b", None, None, "text"), mock.Mock(return_value=p), ttl_seconds=300
        )
        assert cache._thread is thread1

    def test_clear__on_empty_cache__is_noop(self, cache):
        cache.clear()
        assert cache.get(("any", None, None, "text")) is None

    def test_refresh__pinned_entry__not_refreshed_even_after_ttl(self, cache):
        p = _make_mock_prompt()
        callback = mock.Mock(return_value=p)

        base = time.monotonic()
        with mock.patch("opik.api_objects.prompt.prompt_cache.time") as mock_time:
            mock_time.monotonic.return_value = base
            cache.get_or_fetch(("p", "v1", None, "text"), callback, ttl_seconds=None)
            callback.reset_mock()

            mock_time.monotonic.return_value = base + 0.2
            cache._refresh_stale_entries()

            callback.assert_not_called()
        assert cache.get(("p", "v1", None, "text")) is p

    def test_get_or_fetch__fetch_returns_none__not_cached(self, cache):
        fetch_fn = mock.Mock(return_value=None)
        cache.get_or_fetch(("p", None, None, "text"), fetch_fn, ttl_seconds=300)
        assert cache.get(("p", None, None, "text")) is None

    def test_refresh__callback_returns_none__preserves_original_prompt(self):
        original = _make_mock_prompt(commit=None)
        callback = mock.Mock(side_effect=[original, None])

        cache = get_global_cache()
        base = time.monotonic()
        with mock.patch("opik.api_objects.prompt.prompt_cache.time") as mock_time:
            mock_time.monotonic.return_value = base
            prompt_cache.get_or_fetch("p", None, None, "text", callback)

            mock_time.monotonic.return_value = base + _get_ttl() + 1
            cache._refresh_stale_entries()

        assert cache.get(("p", None, None, "text", None)) is original

    def test_lru_eviction__oldest_entry_removed_when_max_size_exceeded(self):
        cache = PromptCache(max_size=3)
        prompts = [_make_mock_prompt(commit=f"c{i}") for i in range(4)]
        for i, p in enumerate(prompts):
            cache.get_or_fetch(
                (f"p{i}", f"c{i}", None, "text"),
                mock.Mock(return_value=p),
                ttl_seconds=None,
            )

        assert cache.get(("p0", "c0", None, "text")) is None
        assert cache.get(("p1", "c1", None, "text")) is prompts[1]
        assert cache.get(("p2", "c2", None, "text")) is prompts[2]
        assert cache.get(("p3", "c3", None, "text")) is prompts[3]
        cache.clear()

    def test_lru_eviction__access_refreshes_position(self):
        cache = PromptCache(max_size=3)
        prompts = [_make_mock_prompt(commit=f"c{i}") for i in range(3)]
        for i, p in enumerate(prompts):
            cache.get_or_fetch(
                (f"p{i}", f"c{i}", None, "text"),
                mock.Mock(return_value=p),
                ttl_seconds=None,
            )

        # Access p0 so it becomes most-recently-used
        cache.get(("p0", "c0", None, "text"))

        # Insert a 4th entry — p1 (the actual LRU) should be evicted, not p0
        p3 = _make_mock_prompt(commit="c3")
        cache.get_or_fetch(
            ("p3", "c3", None, "text"),
            mock.Mock(return_value=p3),
            ttl_seconds=None,
        )

        assert cache.get(("p0", "c0", None, "text")) is prompts[0]
        assert cache.get(("p1", "c1", None, "text")) is None
        assert cache.get(("p2", "c2", None, "text")) is prompts[2]
        assert cache.get(("p3", "c3", None, "text")) is p3
        cache.clear()

    def test_clear__called_twice__is_safe(self, cache):
        p = _make_mock_prompt()
        cache.get_or_fetch(
            ("p", None, None, "text"), mock.Mock(return_value=p), ttl_seconds=300
        )
        cache.clear()
        cache.clear()
        assert cache._thread is None


class TestPromptAutoInjection:
    """Test that get_prompt injects prompts into the active trace/span context via opik_prompts."""

    @staticmethod
    def _make_prompt_with_info_dict(name="my-prompt", commit="abc123", info_dict=None):
        p = _make_mock_prompt(name=name, commit=commit)
        p.__internal_api__to_info_dict__ = mock.Mock(
            return_value=info_dict or {"name": name, "version": {"commit": commit}}
        )
        return p

    def _call_get_prompt(self, cache_return_value):
        from opik.api_objects import opik_client

        client = opik_client.Opik()
        with mock.patch(
            "opik.api_objects.prompt.prompt_cache.get_or_fetch",
            return_value=cache_return_value,
        ):
            return client.get_prompt(
                name="my-prompt", commit="abc123", project_name=None
            )

    def test_get_prompt__in_track_context__injects_into_metadata(self):
        info_dict = {"name": "my-prompt", "version": {"commit": "abc123"}}
        mock_prompt = self._make_prompt_with_info_dict(info_dict=info_dict)

        mock_trace_data = mock.Mock()
        mock_trace_data.metadata = None
        mock_span_data = mock.Mock()
        mock_span_data.metadata = None

        with (
            mock.patch(
                "opik.context_storage.get_trace_data", return_value=mock_trace_data
            ),
            mock.patch(
                "opik.context_storage.top_span_data", return_value=mock_span_data
            ),
        ):
            self._call_get_prompt(mock_prompt)

        mock_trace_data.update.assert_called_once_with(
            metadata={"opik_prompts": [info_dict]}
        )
        mock_span_data.update.assert_called_once_with(
            metadata={"opik_prompts": [info_dict]}
        )

    def test_get_prompt__appends_to_existing_prompts(self):
        existing_prompt_info = {"name": "old-prompt", "version": {"commit": "old123"}}
        new_info_dict = {"name": "my-prompt", "version": {"commit": "abc123"}}
        mock_prompt = self._make_prompt_with_info_dict(info_dict=new_info_dict)

        mock_trace_data = mock.Mock()
        mock_trace_data.metadata = {
            "opik_prompts": [existing_prompt_info],
            "other_key": "value",
        }
        mock_span_data = mock.Mock()
        mock_span_data.metadata = {"opik_prompts": [existing_prompt_info]}

        with (
            mock.patch(
                "opik.context_storage.get_trace_data", return_value=mock_trace_data
            ),
            mock.patch(
                "opik.context_storage.top_span_data", return_value=mock_span_data
            ),
        ):
            self._call_get_prompt(mock_prompt)

        mock_trace_data.update.assert_called_once_with(
            metadata={"opik_prompts": [existing_prompt_info, new_info_dict]}
        )
        mock_span_data.update.assert_called_once_with(
            metadata={"opik_prompts": [existing_prompt_info, new_info_dict]}
        )

    def test_get_prompt__no_track_context__no_error(self):
        """When there is no active trace context, injection silently does nothing."""
        mock_prompt = self._make_prompt_with_info_dict()

        with (
            mock.patch("opik.context_storage.get_trace_data", return_value=None),
            mock.patch("opik.context_storage.top_span_data", return_value=None),
        ):
            result = self._call_get_prompt(mock_prompt)
            assert result is mock_prompt

    def test_get_prompt__none_result__no_injection(self):
        with (
            mock.patch("opik.context_storage.get_trace_data") as mock_get_trace,
            mock.patch("opik.context_storage.top_span_data") as mock_top_span,
        ):
            result = self._call_get_prompt(None)

        assert result is None
        mock_get_trace.assert_not_called()
        mock_top_span.assert_not_called()
