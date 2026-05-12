"""Unit tests for the prompt client-side cache."""

import time
from typing import Optional
from unittest import mock

import pytest

from opik.api_objects.prompt import prompt_cache
from opik.api_objects.prompt.prompt_cache import (
    PromptCacheEntry,
    PromptCacheRefreshThread,
    PromptCacheRegistry,
    get_global_registry,
)


@pytest.fixture(autouse=True)
def clear_caches_after_test():
    yield
    get_global_registry().clear()


@pytest.fixture
def registry():
    r = PromptCacheRegistry()
    yield r
    r.clear()


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


class TestPromptCacheRegistry:
    def test_get__missing_key__returns_none(self, registry):
        assert registry.get(("p", None, None)) is None

    def test_set_then_get__returns_entry(self, registry):
        entry = PromptCacheEntry(prompt=_make_mock_prompt(), pinned=False)
        registry.set(("p", None, None), entry)
        assert registry.get(("p", None, None)) is entry

    def test_clear__empties_registry(self, registry):
        entry = PromptCacheEntry(prompt=_make_mock_prompt(), pinned=False)
        registry.set(("p", None, None), entry)
        registry.clear()
        assert registry.get(("p", None, None)) is None

    def test_clear__stops_thread(self, registry):
        registry.ensure_refresh_thread_started()
        assert registry._thread is not None and registry._thread.is_alive()
        registry.clear()
        assert registry._thread is None

    def test_ensure_refresh_thread_started__starts_thread(self, registry):
        assert registry._thread is None
        registry.ensure_refresh_thread_started()
        assert registry._thread is not None
        assert registry._thread.is_alive()

    def test_ensure_refresh_thread_started__second_call_noop(self, registry):
        registry.ensure_refresh_thread_started()
        first = registry._thread
        registry.ensure_refresh_thread_started()
        assert registry._thread is first

    def test_stop_refresh_thread__stops_and_nulls(self, registry):
        registry.ensure_refresh_thread_started()
        thread = registry._thread
        registry.stop_refresh_thread()
        thread.join(timeout=2)
        assert not thread.is_alive()
        assert registry._thread is None


class TestPromptCacheEntry:
    def test_pinned__never_stale(self):
        entry = PromptCacheEntry(prompt=_make_mock_prompt(), pinned=True, ttl_seconds=0)
        assert not entry.is_stale()

    def test_unpinned__stale_after_ttl(self):
        entry = PromptCacheEntry(
            prompt=_make_mock_prompt(), pinned=False, ttl_seconds=0
        )
        time.sleep(0.01)
        assert entry.is_stale()

    def test_unpinned__not_stale_within_ttl(self):
        entry = PromptCacheEntry(
            prompt=_make_mock_prompt(), pinned=False, ttl_seconds=300
        )
        assert not entry.is_stale()

    def test_update__replaces_prompt_and_resets_freshness(self):
        entry = PromptCacheEntry(
            prompt=_make_mock_prompt(), pinned=False, ttl_seconds=1
        )
        # Force staleness by backdating the last fetch
        entry._last_fetch -= 2
        assert entry.is_stale()

        new_prompt = _make_mock_prompt(commit="newcommit")
        entry.update(new_prompt)
        assert entry.prompt is new_prompt
        assert not entry.is_stale()

    def test_try_background_refresh__no_callback__noop(self):
        entry = PromptCacheEntry(prompt=_make_mock_prompt(), pinned=False)
        entry.try_background_refresh()
        assert entry.prompt.commit == "abc123"

    def test_try_background_refresh__updates_prompt(self):
        new_prompt = _make_mock_prompt(commit="refreshed")
        entry = PromptCacheEntry(
            prompt=_make_mock_prompt(), pinned=False, ttl_seconds=0
        )
        entry.set_refresh_callback(lambda: new_prompt)
        entry.try_background_refresh()
        assert entry.prompt is new_prompt

    def test_try_background_refresh__callback_returns_none__no_change(self):
        original = _make_mock_prompt()
        entry = PromptCacheEntry(prompt=original, pinned=False, ttl_seconds=0)
        entry.set_refresh_callback(lambda: None)
        entry.try_background_refresh()
        assert entry.prompt is original

    def test_try_background_refresh__callback_raises__no_crash(self):
        entry = PromptCacheEntry(prompt=_make_mock_prompt(), pinned=False)
        entry.set_refresh_callback(mock.Mock(side_effect=RuntimeError("boom")))
        entry.try_background_refresh()

    def test_set_refresh_callback__first_wins(self):
        cb1 = mock.Mock(return_value=_make_mock_prompt(commit="cb1"))
        cb2 = mock.Mock(return_value=_make_mock_prompt(commit="cb2"))
        entry = PromptCacheEntry(
            prompt=_make_mock_prompt(), pinned=False, ttl_seconds=0
        )
        entry.set_refresh_callback(cb1)
        entry.set_refresh_callback(cb2)
        entry.try_background_refresh()
        cb1.assert_called_once()
        cb2.assert_not_called()


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

    def test_pinned_commit__no_refresh_callback(self):
        p = _make_mock_prompt(commit="abc")
        fetch_fn = mock.Mock(return_value=p)
        prompt_cache.get_or_fetch("p", "abc", None, "text", fetch_fn)
        key = ("p", "abc", None, "text")
        entry = get_global_registry().get(key)
        assert entry is not None
        assert entry._refresh_callback is None

    def test_unpinned__refresh_callback_registered(self):
        p = _make_mock_prompt(commit=None)
        fetch_fn = mock.Mock(return_value=p)
        prompt_cache.get_or_fetch("p", None, None, "text", fetch_fn)
        key = ("p", None, None, "text")
        entry = get_global_registry().get(key)
        assert entry is not None
        assert entry._refresh_callback is fetch_fn

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


class TestPromptReferences:
    def test_injects_into_trace_and_span_when_in_track_context(self):
        from opik.api_objects.prompt.base_prompt import _prompt_references

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
            _prompt_references(p)

        expected_payload = {
            "_prompt_references": [{"name": "my-prompt", "commit": "abc123"}]
        }
        mock_trace_update.assert_called_once_with(metadata=expected_payload)
        mock_span_update.assert_called_once_with(metadata=expected_payload)

    def test_includes_prompt_id_when_present(self):
        from opik.api_objects.prompt.base_prompt import _prompt_references

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
            _prompt_references(p)

        expected_payload = {
            "_prompt_references": [
                {"name": "my-prompt", "commit": "abc123", "prompt_id": "pid-123"}
            ]
        }
        mock_trace_update.assert_called_once_with(metadata=expected_payload)
        mock_span_update.assert_called_once_with(metadata=expected_payload)

    def test_accumulates_multiple_prompts(self):
        from opik.api_objects.prompt.base_prompt import _prompt_references

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
            _prompt_references(
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
            _prompt_references(
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

    def test_no_injection_outside_track_context(self):
        from opik.api_objects.prompt.base_prompt import _prompt_references

        with (
            mock.patch("opik.opik_context.get_current_trace_data", return_value=None),
            mock.patch("opik.opik_context.get_current_span_data", return_value=None),
            mock.patch("opik.opik_context.update_current_trace") as mock_trace_update,
            mock.patch("opik.opik_context.update_current_span") as mock_span_update,
        ):
            _prompt_references(_make_mock_prompt())

        mock_trace_update.assert_not_called()
        mock_span_update.assert_not_called()


class TestPromptCacheRefreshThread:
    def test_stops_on_close(self):
        thread = PromptCacheRefreshThread(get_entries=list, interval_seconds=0.01)
        thread.start()
        assert thread.is_alive()
        thread.close()
        thread.join(timeout=2)
        assert not thread.is_alive()

    def test_refreshes_stale_entry(self, registry):
        new_prompt = _make_mock_prompt(commit="refreshed")
        callback = mock.Mock(return_value=new_prompt)

        entry = PromptCacheEntry(
            prompt=_make_mock_prompt(), pinned=False, ttl_seconds=0
        )
        entry.set_refresh_callback(callback)
        registry.set(("p", None, None), entry)

        thread = PromptCacheRefreshThread(
            get_entries=registry.get_entries,
            interval_seconds=0.05,
        )
        thread.start()
        try:
            time.sleep(0.3)
            assert callback.call_count >= 1
            assert entry.prompt is new_prompt
        finally:
            thread.close()
            thread.join(timeout=2)

    def test_skips_non_stale_entry(self, registry):
        entry = PromptCacheEntry(
            prompt=_make_mock_prompt(), pinned=False, ttl_seconds=300
        )
        callback = mock.Mock()
        entry.set_refresh_callback(callback)
        registry.set(("p", None, None), entry)

        thread = PromptCacheRefreshThread(
            get_entries=registry.get_entries,
            interval_seconds=0.05,
        )
        thread.start()
        try:
            time.sleep(0.2)
            callback.assert_not_called()
        finally:
            thread.close()
            thread.join(timeout=2)
