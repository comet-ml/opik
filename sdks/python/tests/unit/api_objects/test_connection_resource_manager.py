import threading
import time
from typing import List, Optional, Tuple
from unittest import mock

from opik import config as opik_config
from opik.api_objects import connection_resources


class FakeBundle:
    """Stand-in for SharedConnectionResourcesBundle that records close/flush calls."""

    def __init__(self) -> None:
        self.flush_timeout: Optional[int] = None
        self.close_calls: List[Tuple[Optional[int], bool]] = []
        self.flush_calls: List[Optional[int]] = []

    @property
    def closed(self) -> bool:
        return len(self.close_calls) > 0

    def close(self, timeout: Optional[int], *, flush: bool) -> None:
        self.close_calls.append((timeout, flush))

    def flush(self, timeout: Optional[int]) -> None:
        self.flush_calls.append(timeout)


def _manager():
    """Returns (manager, created) where ``created`` accumulates built bundles.

    A fake builder keeps these tests focused on the ref-counting lifecycle
    without spinning up real transport threads.
    """
    created: List[FakeBundle] = []

    def builder(config, *, use_batching):
        bundle = FakeBundle()
        created.append(bundle)
        return bundle

    return connection_resources.ConnectionResourceManager(builder=builder), created


def _config(workspace: str = "default") -> opik_config.OpikConfig:
    return opik_config.OpikConfig(workspace=workspace)


def test_acquire__same_config__reuses_single_bundle():
    manager, created = _manager()
    config = _config()

    lease_a = manager.acquire(config, use_batching=True)
    lease_b = manager.acquire(config, use_batching=True)

    assert len(created) == 1
    assert lease_a.resources is lease_b.resources is created[0]
    assert manager.reference_count(config, use_batching=True) == 2
    assert manager.active_connection_count() == 1


def test_acquire__distinct_configs__builds_separate_bundles():
    manager, created = _manager()
    config_a = _config("ws-a")
    config_b = _config("ws-b")

    lease_a = manager.acquire(config_a, use_batching=True)
    lease_b = manager.acquire(config_b, use_batching=True)

    assert len(created) == 2
    assert lease_a.resources is not lease_b.resources
    assert manager.active_connection_count() == 2


def test_acquire__differing_use_batching__builds_separate_bundles():
    manager, created = _manager()
    config = _config()

    manager.acquire(config, use_batching=True)
    manager.acquire(config, use_batching=False)

    assert len(created) == 2
    assert manager.active_connection_count() == 2


def test_release__not_last_reference__keeps_bundle_open():
    manager, created = _manager()
    config = _config()
    lease_a = manager.acquire(config, use_batching=True)
    manager.acquire(config, use_batching=True)

    lease_a.release(timeout=None, close_on_zero=True)

    assert not created[0].closed
    # A durable release (flush=True, the default) still drains the shared queue
    # so this handle's data is persisted while the bundle stays alive.
    assert created[0].flush_calls == [None]
    assert manager.reference_count(config, use_batching=True) == 1


def test_release__not_last_reference_with_flush__drains_shared_queue_without_closing():
    # Durability contract under sharing: end(flush=True) on a handle that shares
    # its bundle must flush the shared queue now — otherwise a co-located
    # handle's later flush=False teardown could discard this handle's data.
    manager, created = _manager()
    config = _config()
    lease_a = manager.acquire(config, use_batching=True)
    manager.acquire(config, use_batching=True)

    lease_a.release(timeout=3, flush=True, close_on_zero=True)

    assert created[0].flush_calls == [3]
    assert not created[0].closed
    assert manager.reference_count(config, use_batching=True) == 1


def test_release__not_last_reference_flush_false__neither_flushes_nor_closes():
    manager, created = _manager()
    config = _config()
    lease_a = manager.acquire(config, use_batching=True)
    manager.acquire(config, use_batching=True)

    lease_a.release(timeout=None, flush=False, close_on_zero=True)

    assert created[0].flush_calls == []
    assert not created[0].closed


def test_release__not_last_reference_gc_path__never_flushes():
    # A GC finalizer (close_on_zero=False) must never do network I/O, even though
    # it releases with flush=True by default.
    manager, created = _manager()
    config = _config()
    lease_a = manager.acquire(config, use_batching=True)
    manager.acquire(config, use_batching=True)

    lease_a.release(timeout=None, close_on_zero=False)

    assert created[0].flush_calls == []
    assert not created[0].closed
    assert manager.reference_count(config, use_batching=True) == 1


def test_release__concurrent_durable_and_teardown__no_close_during_shared_flush():
    # Regression: a shared end(flush=True) pre-flushes while still holding its
    # reference, so a concurrent last-release (flush=False) cannot evict + close
    # the bundle and clear the queue mid-flush. The event forces the teardown to
    # race the in-flight shared flush; on the buggy (decrement-then-flush) order
    # the close would run while in_flush is True.
    flush_started = threading.Event()

    class RaceDetectBundle(FakeBundle):
        def __init__(self) -> None:
            super().__init__()
            self.in_flush = False
            self.closed_during_flush = False

        def flush(self, timeout: Optional[int]) -> None:
            self.in_flush = True
            flush_started.set()
            time.sleep(0.1)  # window in which a racing close must not run
            self.in_flush = False
            super().flush(timeout)

        def close(self, timeout: Optional[int], *, flush: bool) -> None:
            if self.in_flush:
                self.closed_during_flush = True
            super().close(timeout, flush=flush)

    bundle = RaceDetectBundle()
    manager = connection_resources.ConnectionResourceManager(
        builder=lambda config, *, use_batching: bundle
    )
    config = _config()
    lease_a = manager.acquire(config, use_batching=True)  # durable holder
    lease_b = manager.acquire(config, use_batching=True)  # fire-and-forget holder

    def durable_release() -> None:
        lease_a.release(timeout=None, flush=True, close_on_zero=True)

    def teardown_release() -> None:
        flush_started.wait(timeout=2)  # release into the in-flight shared flush
        lease_b.release(timeout=None, flush=False, close_on_zero=True)

    threads = [
        threading.Thread(target=durable_release),
        threading.Thread(target=teardown_release),
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert not bundle.closed_during_flush  # close never ran mid-flush
    assert bundle.closed  # bundle still torn down once, after the flush
    assert bundle.flush_calls == [None]  # the durable holder drained the queue
    assert manager.active_connection_count() == 0


def test_release__last_reference__closes_and_evicts():
    manager, created = _manager()
    config = _config()
    lease_a = manager.acquire(config, use_batching=True)
    lease_b = manager.acquire(config, use_batching=True)

    lease_a.release(timeout=None, close_on_zero=True)
    lease_b.release(timeout=None, close_on_zero=True)

    assert created[0].close_calls == [(None, True)]
    assert manager.reference_count(config, use_batching=True) == 0
    assert manager.active_connection_count() == 0


def test_release__last_reference__forwards_timeout_and_flush():
    manager, created = _manager()
    lease = manager.acquire(_config(), use_batching=True)

    lease.release(timeout=7, flush=False, close_on_zero=True)

    assert created[0].close_calls == [(7, False)]


def test_release__not_close_on_zero__last_reference__decrements_without_closing():
    # The GC-finalizer path: dropping the last reference must only decrement.
    # Closing (thread joins, network flush) is never safe inside garbage
    # collection, so the bundle is left cached instead.
    manager, created = _manager()
    config = _config()
    lease = manager.acquire(config, use_batching=True)

    lease.release(timeout=None, close_on_zero=False)

    assert not created[0].closed
    assert manager.reference_count(config, use_batching=True) == 0
    assert manager.active_connection_count() == 1  # still cached, not evicted


def test_acquire__after_gc_release__reuses_cached_bundle():
    # A bundle left cached by a close_on_zero=False release is reused by the
    # next same-identity acquire rather than rebuilt.
    manager, created = _manager()
    config = _config()
    lease = manager.acquire(config, use_batching=True)
    lease.release(timeout=None, close_on_zero=False)

    lease_again = manager.acquire(config, use_batching=True)

    assert len(created) == 1
    assert lease_again.resources is created[0]
    assert manager.reference_count(config, use_batching=True) == 1


def test_close_all__after_gc_release__disposes_cached_bundle():
    # Whatever a GC release leaves cached is still disposed at process exit.
    manager, created = _manager()
    config = _config()
    lease = manager.acquire(config, use_batching=True)
    lease.release(timeout=None, close_on_zero=False)

    manager.close_all()

    assert created[0].close_calls == [(None, True)]
    assert manager.active_connection_count() == 0


def test_release__called_twice__decrements_once():
    manager, created = _manager()
    config = _config()
    lease_a = manager.acquire(config, use_batching=True)
    lease_b = manager.acquire(config, use_batching=True)

    lease_a.release(timeout=None, close_on_zero=True)
    lease_a.release(
        timeout=None, close_on_zero=True
    )  # idempotent — must not decrement again

    assert not created[0].closed
    assert manager.reference_count(config, use_batching=True) == 1

    lease_b.release(timeout=None, close_on_zero=True)
    assert created[0].close_calls == [(None, True)]
    assert manager.reference_count(config, use_batching=True) == 0


def test_acquire__after_full_release__builds_fresh_bundle():
    manager, created = _manager()
    config = _config()
    lease = manager.acquire(config, use_batching=True)
    lease.release(timeout=None, close_on_zero=True)

    lease_again = manager.acquire(config, use_batching=True)

    assert len(created) == 2
    assert lease_again.resources is created[1]


def test_close_all__cached_bundles__closed_and_cleared():
    manager, created = _manager()
    manager.acquire(_config("ws-a"), use_batching=True)
    manager.acquire(_config("ws-b"), use_batching=True)

    manager.close_all()

    assert manager.active_connection_count() == 0
    # Each bundle is closed with its own configured flush timeout (None here).
    assert all(bundle.close_calls == [(None, True)] for bundle in created)


def test_acquire_release__concurrent_same_config__preserves_invariants():
    manager, created = _manager()
    config = _config()

    thread_count = 16
    iterations = 50
    barrier = threading.Barrier(thread_count)
    errors: List[str] = []

    def worker() -> None:
        barrier.wait()
        for _ in range(iterations):
            lease = manager.acquire(config, use_batching=True)
            # While a reference is held, the bundle must never be torn down —
            # the manager must not hand out a closing bundle.
            if lease.resources.closed:
                errors.append("acquired a bundle that was already closed")
            lease.release(timeout=None, close_on_zero=True)

    threads = [threading.Thread(target=worker) for _ in range(thread_count)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert errors == []
    assert manager.reference_count(config, use_batching=True) == 0
    assert manager.active_connection_count() == 0
    # Every bundle that was ever built must have been closed exactly once.
    for bundle in created:
        assert len(bundle.close_calls) == 1


def _bundle_with_mock_transport(flush_timeout=None):
    streamer = mock.Mock()
    file_upload_manager = mock.Mock()
    httpx_client = mock.Mock()
    bundle = connection_resources.SharedConnectionResourcesBundle(
        httpx_client=httpx_client,
        rest_client=mock.Mock(),
        message_processor=mock.Mock(),
        file_upload_manager=file_upload_manager,
        replay_manager=mock.Mock(),
        streamer=streamer,
        flush_timeout=flush_timeout,
    )
    return bundle, streamer, file_upload_manager, httpx_client


def test_bundle_close__flush_true__drains_streamer_and_upload_pool():
    bundle, streamer, file_upload_manager, httpx_client = _bundle_with_mock_transport()

    bundle.close(5, flush=True)

    # flush=True finalizes pending data: the streamer drains its queue and
    # flushes uploads, and the upload pool waits for in-flight uploads.
    streamer.close.assert_called_once_with(5, flush=True)
    file_upload_manager.close.assert_called_once_with(wait=True)
    # On a durable close the replay thread is joined and uploads drained, so the
    # httpx pool is released too — eviction leaks no sockets.
    httpx_client.close.assert_called_once_with()


def test_bundle_close__flush_false__stops_without_waiting():
    bundle, streamer, file_upload_manager, httpx_client = _bundle_with_mock_transport()

    bundle.close(None, flush=False)

    streamer.close.assert_called_once_with(None, flush=False)
    file_upload_manager.close.assert_called_once_with(wait=False)
    # flush=False is fire-and-forget: the streamer leaves daemon threads to
    # finish in-flight requests, so the shared httpx pool must NOT be closed here
    # (closing it would race those requests). It's released at GC / process exit.
    httpx_client.close.assert_not_called()


def test_bundle_flush__drains_streamer_without_closing():
    bundle, streamer, file_upload_manager, httpx_client = _bundle_with_mock_transport()

    bundle.flush(4)

    # flush() drains the queue but must not tear anything down — the bundle is
    # still shared by other handles.
    streamer.flush.assert_called_once_with(4)
    streamer.close.assert_not_called()
    file_upload_manager.close.assert_not_called()
    httpx_client.close.assert_not_called()
