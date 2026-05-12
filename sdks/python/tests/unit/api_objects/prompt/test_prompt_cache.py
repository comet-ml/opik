"""Unit tests for the prompt client-side cache."""

import time
from typing import Optional
from unittest import mock

import pytest

from opik.api_objects.prompt import prompt_cache
from opik.api_objects.prompt.prompt_cache import (
    PromptCache,
    get_global_cache,
)


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

    def test_get_or_fetch__caches_and_returns(self, cache):
        p = _make_mock_prompt()
        fetch_fn = mock.Mock(return_value=p)
        result = cache.get_or_fetch(("p", None, None, "text"), fetch_fn, pinned=False)
        assert result is p
        fetch_fn.assert_called_once()

    def test_get_or_fetch__hit_does_not_call_fetch_fn(self, cache):
        p = _make_mock_prompt()
        fetch_fn = mock.Mock(return_value=p)
        cache.get_or_fetch(("p", None, None, "text"), fetch_fn, pinned=False)
        fetch_fn.reset_mock()
        result = cache.get_or_fetch(("p", None, None, "text"), fetch_fn, pinned=False)
        assert result is p
        fetch_fn.assert_not_called()

    def test_get_or_fetch__fetch_returns_none__returns_none(self, cache):
        fetch_fn = mock.Mock(return_value=None)
        result = cache.get_or_fetch(
            ("missing", None, None, "text"), fetch_fn, pinned=False
        )
        assert result is None

    def test_clear__empties_cache(self, cache):
        p = _make_mock_prompt()
        cache.get_or_fetch(
            ("p", None, None, "text"), mock.Mock(return_value=p), pinned=True
        )
        cache.clear()
        assert cache.get(("p", None, None, "text")) is None

    def test_clear__stops_thread(self, cache):
        p = _make_mock_prompt()
        cache.get_or_fetch(
            ("p", None, None, "text"), mock.Mock(return_value=p), pinned=False
        )
        assert cache._thread is not None and cache._thread.is_alive()
        cache.clear()
        assert cache._thread is None

    def test_refresh_thread__started_for_unpinned(self, cache):
        p = _make_mock_prompt()
        cache.get_or_fetch(
            ("p", None, None, "text"), mock.Mock(return_value=p), pinned=False
        )
        assert cache._thread is not None
        assert cache._thread.is_alive()

    def test_refresh_thread__not_started_for_pinned(self, cache):
        p = _make_mock_prompt()
        cache.get_or_fetch(
            ("p", None, None, "text"), mock.Mock(return_value=p), pinned=True
        )
        assert cache._thread is None

    def test_different_keys__different_entries(self, cache):
        p1 = _make_mock_prompt(commit="c1")
        p2 = _make_mock_prompt(commit="c2")
        cache.get_or_fetch(
            ("p", "c1", None, "text"), mock.Mock(return_value=p1), pinned=True
        )
        cache.get_or_fetch(
            ("p", "c2", None, "text"), mock.Mock(return_value=p2), pinned=True
        )
        assert cache.get(("p", "c1", None, "text")) is p1
        assert cache.get(("p", "c2", None, "text")) is p2


class TestBackgroundRefresh:
    def test_refresh_updates_stale_entry(self, cache):
        new_prompt = _make_mock_prompt(commit="refreshed")
        callback = mock.Mock(return_value=new_prompt)

        with mock.patch(
            "opik.api_objects.prompt.prompt_cache._PROMPT_CACHE_TTL_SECONDS", 0
        ):
            cache.get_or_fetch(("p", None, None, "text"), callback, pinned=False)
            callback.reset_mock()
            callback.return_value = new_prompt
            time.sleep(0.3)
            assert callback.call_count >= 1
            assert cache.get(("p", None, None, "text")) is new_prompt

    def test_refresh_skips_non_stale_entry(self, cache):
        p = _make_mock_prompt()
        callback = mock.Mock(return_value=p)
        cache.get_or_fetch(("p", None, None, "text"), callback, pinned=False)
        callback.reset_mock()
        time.sleep(0.2)
        callback.assert_not_called()

    def test_refresh_callback_error_does_not_crash(self, cache):
        p = _make_mock_prompt()
        callback = mock.Mock(side_effect=[p, RuntimeError("boom")])

        with mock.patch(
            "opik.api_objects.prompt.prompt_cache._PROMPT_CACHE_TTL_SECONDS", 0
        ):
            cache.get_or_fetch(("p", None, None, "text"), callback, pinned=False)
            time.sleep(0.3)
            assert cache._thread.is_alive()


class TestGetOrFetch:
    def test_miss__calls_fetch_fn(self):
        p = _make_mock_prompt()
        fetch_fn = mock.Mock(return_value=p)
        result = prompt_cache.get_or_fetch("p", None, None, "text", fetch_fn)
        assert result is p
        fetch_fn.assert_called_once()

    def test_miss__fetch_returns_none__returns_none(self):
        fetch_fn = mock.Mock(return_value=None)
        result = prompt_cache.get_or_fetch("missing", None, None, "text", fetch_fn)
        assert result is None

    def test_hit__does_not_call_fetch_fn(self):
        p = _make_mock_prompt()
        fetch_fn = mock.Mock(return_value=p)
        prompt_cache.get_or_fetch("p", None, None, "text", fetch_fn)
        fetch_fn.reset_mock()

        result = prompt_cache.get_or_fetch("p", None, None, "text", fetch_fn)
        assert result is p
        fetch_fn.assert_not_called()

    def test_pinned_commit__no_refresh_thread(self):
        p = _make_mock_prompt(commit="abc")
        fetch_fn = mock.Mock(return_value=p)
        prompt_cache.get_or_fetch("p", "abc", None, "text", fetch_fn)
        cache = get_global_cache()
        assert cache._thread is None

    def test_unpinned__starts_refresh_thread(self):
        p = _make_mock_prompt(commit=None)
        fetch_fn = mock.Mock(return_value=p)
        prompt_cache.get_or_fetch("p", None, None, "text", fetch_fn)
        cache = get_global_cache()
        assert cache._thread is not None
        assert cache._thread.is_alive()

    def test_cache_hit__same_object_returned(self):
        p = _make_mock_prompt()
        fetch_fn = mock.Mock(return_value=p)
        prompt_cache.get_or_fetch("p", None, "proj", "text", fetch_fn)
        first = prompt_cache.get_or_fetch("p", None, "proj", "text", fetch_fn)
        second = prompt_cache.get_or_fetch("p", None, "proj", "text", fetch_fn)
        assert first is second is p

    def test_different_keys__different_entries(self):
        p1 = _make_mock_prompt(commit="c1")
        p2 = _make_mock_prompt(commit="c2")
        prompt_cache.get_or_fetch("p", "c1", None, "text", mock.Mock(return_value=p1))
        prompt_cache.get_or_fetch("p", "c2", None, "text", mock.Mock(return_value=p2))
        r1 = prompt_cache.get_or_fetch("p", "c1", None, "text", mock.Mock())
        r2 = prompt_cache.get_or_fetch("p", "c2", None, "text", mock.Mock())
        assert r1 is p1
        assert r2 is p2


class TestPromptCacheEdgeCases:
    def test_multiple_unpinned_inserts__single_refresh_thread(self, cache):
        p = _make_mock_prompt()
        cache.get_or_fetch(
            ("a", None, None, "text"), mock.Mock(return_value=p), pinned=False
        )
        thread1 = cache._thread
        cache.get_or_fetch(
            ("b", None, None, "text"), mock.Mock(return_value=p), pinned=False
        )
        assert cache._thread is thread1

    def test_clear__on_empty_cache__is_noop(self, cache):
        cache.clear()
        assert cache.get(("any", None, None, "text")) is None

    def test_pinned_entry__not_refreshed_even_after_ttl(self, cache):
        p = _make_mock_prompt()
        callback = mock.Mock(return_value=p)
        with mock.patch(
            "opik.api_objects.prompt.prompt_cache._PROMPT_CACHE_TTL_SECONDS", 0
        ):
            cache.get_or_fetch(("p", "v1", None, "text"), callback, pinned=True)
            callback.reset_mock()
            time.sleep(0.2)
            callback.assert_not_called()
        assert cache.get(("p", "v1", None, "text")) is p

    def test_fetch_returns_none__not_cached(self, cache):
        fetch_fn = mock.Mock(return_value=None)
        cache.get_or_fetch(("p", None, None, "text"), fetch_fn, pinned=False)
        assert cache.get(("p", None, None, "text")) is None

    def test_refresh_returning_none__preserves_original_prompt(self, cache):
        original = _make_mock_prompt(commit="original")
        callback = mock.Mock(side_effect=[original, None])

        with mock.patch(
            "opik.api_objects.prompt.prompt_cache._PROMPT_CACHE_TTL_SECONDS", 0
        ):
            cache.get_or_fetch(("p", None, None, "text"), callback, pinned=False)
            time.sleep(0.3)
            assert cache.get(("p", None, None, "text")) is original

    def test_lru_eviction__oldest_entry_removed_when_max_size_exceeded(self):
        cache = PromptCache(max_size=3)
        prompts = [_make_mock_prompt(commit=f"c{i}") for i in range(4)]
        for i, p in enumerate(prompts):
            cache.get_or_fetch(
                (f"p{i}", f"c{i}", None, "text"),
                mock.Mock(return_value=p),
                pinned=True,
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
                pinned=True,
            )

        # Access p0 so it becomes most-recently-used
        cache.get(("p0", "c0", None, "text"))

        # Insert a 4th entry — p1 (the actual LRU) should be evicted, not p0
        p3 = _make_mock_prompt(commit="c3")
        cache.get_or_fetch(
            ("p3", "c3", None, "text"),
            mock.Mock(return_value=p3),
            pinned=True,
        )

        assert cache.get(("p0", "c0", None, "text")) is prompts[0]
        assert cache.get(("p1", "c1", None, "text")) is None
        assert cache.get(("p2", "c2", None, "text")) is prompts[2]
        assert cache.get(("p3", "c3", None, "text")) is p3
        cache.clear()

    def test_clear_after_clear__is_safe(self, cache):
        p = _make_mock_prompt()
        cache.get_or_fetch(
            ("p", None, None, "text"), mock.Mock(return_value=p), pinned=False
        )
        cache.clear()
        cache.clear()
        assert cache._thread is None


class TestPromptReferences:
    def test_prompt_reference__in_track_context__injects_into_trace_and_span(self):
        from opik.api_objects.prompt.base_prompt import prompt_reference

        mock_trace_data = mock.Mock()
        mock_trace_data.metadata = None
        mock_span_data = mock.Mock()
        mock_span_data.metadata = None

        with (
            mock.patch(
                "opik.opik_context.get_current_trace_data", return_value=mock_trace_data
            ),
            mock.patch(
                "opik.opik_context.get_current_span_data", return_value=mock_span_data
            ),
            mock.patch("opik.opik_context.update_current_trace") as mock_trace_update,
            mock.patch("opik.opik_context.update_current_span") as mock_span_update,
        ):
            p = _make_mock_prompt(name="my-prompt", commit="abc123")
            prompt_reference(p)

        expected_payload = {
            "_prompt_references": [{"name": "my-prompt", "commit": "abc123"}]
        }
        mock_trace_update.assert_called_once_with(metadata=expected_payload)
        mock_span_update.assert_called_once_with(metadata=expected_payload)

    def test_prompt_reference__prompt_id_present__includes_prompt_id(self):
        from opik.api_objects.prompt.base_prompt import prompt_reference

        mock_trace_data = mock.Mock()
        mock_trace_data.metadata = None
        mock_span_data = mock.Mock()
        mock_span_data.metadata = None

        with (
            mock.patch(
                "opik.opik_context.get_current_trace_data", return_value=mock_trace_data
            ),
            mock.patch(
                "opik.opik_context.get_current_span_data", return_value=mock_span_data
            ),
            mock.patch("opik.opik_context.update_current_trace") as mock_trace_update,
            mock.patch("opik.opik_context.update_current_span") as mock_span_update,
        ):
            p = _make_mock_prompt(
                name="my-prompt", commit="abc123", prompt_id="pid-123"
            )
            prompt_reference(p)

        expected_payload = {
            "_prompt_references": [
                {"name": "my-prompt", "commit": "abc123", "prompt_id": "pid-123"}
            ]
        }
        mock_trace_update.assert_called_once_with(metadata=expected_payload)
        mock_span_update.assert_called_once_with(metadata=expected_payload)

    def test_prompt_reference__multiple_prompts__accumulates_refs(self):
        from opik.api_objects.prompt.base_prompt import prompt_reference

        mock_trace_data = mock.Mock()
        mock_trace_data.metadata = None
        mock_span_data = mock.Mock()
        mock_span_data.metadata = None

        with (
            mock.patch(
                "opik.opik_context.get_current_trace_data", return_value=mock_trace_data
            ),
            mock.patch(
                "opik.opik_context.get_current_span_data", return_value=mock_span_data
            ),
            mock.patch("opik.opik_context.update_current_trace") as mock_trace_update,
            mock.patch("opik.opik_context.update_current_span") as mock_span_update,
        ):
            prompt_reference(
                _make_mock_prompt(name="p1", commit="c1", prompt_id="pid-1")
            )
            mock_trace_data.metadata = {
                "_prompt_references": [
                    {"name": "p1", "commit": "c1", "prompt_id": "pid-1"}
                ]
            }
            mock_span_data.metadata = {
                "_prompt_references": [
                    {"name": "p1", "commit": "c1", "prompt_id": "pid-1"}
                ]
            }
            prompt_reference(
                _make_mock_prompt(name="p2", commit="c2", prompt_id="pid-2")
            )

        assert mock_trace_update.call_count == 2
        assert mock_span_update.call_count == 2
        assert mock_trace_update.call_args == mock.call(
            metadata={
                "_prompt_references": [
                    {"name": "p1", "commit": "c1", "prompt_id": "pid-1"},
                    {"name": "p2", "commit": "c2", "prompt_id": "pid-2"},
                ]
            }
        )
        assert mock_span_update.call_args == mock.call(
            metadata={
                "_prompt_references": [
                    {"name": "p1", "commit": "c1", "prompt_id": "pid-1"},
                    {"name": "p2", "commit": "c2", "prompt_id": "pid-2"},
                ]
            }
        )

    def test_prompt_reference__no_track_context__no_injection(self):
        from opik.api_objects.prompt.base_prompt import prompt_reference

        with (
            mock.patch("opik.opik_context.get_current_trace_data", return_value=None),
            mock.patch("opik.opik_context.get_current_span_data", return_value=None),
            mock.patch("opik.opik_context.update_current_trace") as mock_trace_update,
            mock.patch("opik.opik_context.update_current_span") as mock_span_update,
        ):
            prompt_reference(_make_mock_prompt())

        mock_trace_update.assert_not_called()
        mock_span_update.assert_not_called()
