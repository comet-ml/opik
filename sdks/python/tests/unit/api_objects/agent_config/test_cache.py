import threading
import time
from unittest import mock

import pytest

from opik.api_objects.agent_config.cache import (
    CacheRefreshThread,
    SharedCacheRegistry,
    SharedConfigCache,
)


@pytest.fixture
def registry():
    r = SharedCacheRegistry()
    yield r
    r.clear()


class TestSharedCacheRegistry:
    def test_get__same_key__returns_same_instance(self, registry):
        a = registry.get("proj", None, None)
        b = registry.get("proj", None, None)
        assert a is b

    def test_get__different_key__returns_different_instance(self, registry):
        a = registry.get("proj-a", None, None)
        b = registry.get("proj-b", None, None)
        assert a is not b

    def test_clear__empties_registry(self, registry):
        registry.get("proj", None, None)
        registry.clear()
        # After clear, a new call returns a fresh instance
        fresh = registry.get("proj", None, None)
        assert fresh.blueprint_id is None

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
        first_thread = registry._thread
        registry.ensure_refresh_thread_started()
        assert registry._thread is first_thread

    def test_stop_refresh_thread__stops_and_nulls(self, registry):
        registry.ensure_refresh_thread_started()
        thread = registry._thread
        registry.stop_refresh_thread()
        thread.join(timeout=2)
        assert not thread.is_alive()
        assert registry._thread is None

    def test_concurrent_get__returns_same_instance(self, registry):
        results = [None] * 10
        barrier = threading.Barrier(10)

        def fetch(idx):
            barrier.wait()
            results[idx] = registry.get("proj", None, None)

        threads = [threading.Thread(target=fetch, args=(i,)) for i in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert all(r is results[0] for r in results)


class TestSharedConfigCacheThreadSafety:
    def test_apply__concurrent_reads_see_consistent_dict(self):
        cache = SharedConfigCache(ttl_seconds=300)
        errors = []
        stop = threading.Event()

        def writer():
            for i in range(100):
                bp = mock.Mock()
                bp.id = f"bp-{i}"
                bp._values = {f"key-{j}": f"val-{i}" for j in range(5)}
                cache.update(bp)

        def reader():
            while not stop.is_set():
                vals = cache.values
                unique_vals = set(vals.values())
                if len(unique_vals) > 1:
                    errors.append(f"Inconsistent values: {vals}")
                    break

        writer_thread = threading.Thread(target=writer)
        reader_thread = threading.Thread(target=reader)
        reader_thread.start()
        writer_thread.start()
        writer_thread.join()
        stop.set()
        reader_thread.join()

        assert errors == [], f"Found inconsistencies: {errors}"

    def test_register_fields__concurrent__all_fields_present(self):
        cache = SharedConfigCache()
        barrier = threading.Barrier(4)

        def register(prefix: str):
            barrier.wait()
            cache.register_fields({f"{prefix}.f1": str, f"{prefix}.f2": int})

        threads = [
            threading.Thread(target=register, args=(f"Class{i}",)) for i in range(4)
        ]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        all_types = cache.all_field_types
        assert len(all_types) == 8
        for i in range(4):
            assert f"Class{i}.f1" in all_types
            assert f"Class{i}.f2" in all_types


class TestRefreshCallback:
    def test_set_refresh_callback__first_writer_wins(self):
        cache = SharedConfigCache()
        cb1 = mock.Mock()
        cb2 = mock.Mock()

        cache.set_refresh_callback(cb1)
        cache.set_refresh_callback(cb2)

        cache.try_background_refresh()
        cb1.assert_called_once()
        cb2.assert_not_called()

    def test_try_background_refresh__no_callback__noop(self):
        cache = SharedConfigCache()
        cache.try_background_refresh()

    def test_try_background_refresh__callback_returns_blueprint__applies(self):
        cache = SharedConfigCache(ttl_seconds=300)
        bp = mock.Mock()
        bp.id = "bp-new"
        bp._values = {"A.x": 42}

        cache.set_refresh_callback(lambda: bp)
        cache.try_background_refresh()

        assert cache.blueprint_id == "bp-new"
        assert cache.values == {"A.x": 42}
        assert not cache.is_stale()

    def test_try_background_refresh__callback_returns_none__no_change(self):
        cache = SharedConfigCache()
        cache.set_refresh_callback(lambda: None)
        cache.try_background_refresh()

        assert cache.blueprint_id is None
        assert cache.values == {}

    def test_try_background_refresh__callback_raises__no_crash(self):
        cache = SharedConfigCache()
        cache.set_refresh_callback(mock.Mock(side_effect=RuntimeError("boom")))
        cache.try_background_refresh()

        assert cache.blueprint_id is None


class TestCacheRefreshThread:
    def test_stops_on_close(self):
        thread = CacheRefreshThread(get_caches=list, interval_seconds=0.01)
        thread.start()
        assert thread.is_alive()

        thread.close()
        thread.join(timeout=2)
        assert not thread.is_alive()

    def test_refreshes_stale_cache(self, registry):
        bp = mock.Mock()
        bp.id = "bp-bg"
        bp._values = {"K.v": "refreshed"}

        callback = mock.Mock(return_value=bp)

        cache = registry.get("bg-proj", None, None)
        cache._ttl_seconds = 0
        cache.set_refresh_callback(callback)

        thread = CacheRefreshThread(
            get_caches=lambda: list(registry._caches.values()),
            interval_seconds=0.05,
        )
        thread.start()
        try:
            time.sleep(0.3)
            assert callback.call_count >= 1
            assert cache.values == {"K.v": "refreshed"}
        finally:
            thread.close()
            thread.join(timeout=2)

    def test_skips_non_stale_caches(self, registry):
        bp = mock.Mock()
        bp.id = "bp-init"
        bp._values = {"K.v": "initial"}

        cache = registry.get("fresh-proj", None, None)
        cache.update(bp)
        callback = mock.Mock()
        cache.set_refresh_callback(callback)

        thread = CacheRefreshThread(
            get_caches=lambda: list(registry._caches.values()),
            interval_seconds=0.05,
        )
        thread.start()
        try:
            time.sleep(0.2)
            callback.assert_not_called()
        finally:
            thread.close()
            thread.join(timeout=2)
