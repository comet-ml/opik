import threading
from typing import List, Optional, Tuple
from unittest import mock

from opik import config as opik_config
from opik.api_objects import connection_resources


class FakeBundle:
    """Stand-in for SharedConnectionResourcesBundle that records close calls."""

    def __init__(self) -> None:
        self.flush_timeout: Optional[int] = None
        self.close_calls: List[Tuple[Optional[int], bool]] = []

    @property
    def closed(self) -> bool:
        return len(self.close_calls) > 0

    def close(self, timeout: Optional[int], *, flush: bool) -> None:
        self.close_calls.append((timeout, flush))


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

    lease_a.release(timeout=None)

    assert not created[0].closed
    assert manager.reference_count(config, use_batching=True) == 1


def test_release__last_reference__closes_and_evicts():
    manager, created = _manager()
    config = _config()
    lease_a = manager.acquire(config, use_batching=True)
    lease_b = manager.acquire(config, use_batching=True)

    lease_a.release(timeout=None)
    lease_b.release(timeout=None)

    assert created[0].close_calls == [(None, True)]
    assert manager.reference_count(config, use_batching=True) == 0
    assert manager.active_connection_count() == 0


def test_release__last_reference__forwards_timeout_and_flush():
    manager, created = _manager()
    lease = manager.acquire(_config(), use_batching=True)

    lease.release(timeout=7, flush=False)

    assert created[0].close_calls == [(7, False)]


def test_release__called_twice__decrements_once():
    manager, created = _manager()
    config = _config()
    lease_a = manager.acquire(config, use_batching=True)
    lease_b = manager.acquire(config, use_batching=True)

    lease_a.release(timeout=None)
    lease_a.release(timeout=None)  # idempotent — must not decrement again

    assert not created[0].closed
    assert manager.reference_count(config, use_batching=True) == 1

    lease_b.release(timeout=None)
    assert created[0].close_calls == [(None, True)]
    assert manager.reference_count(config, use_batching=True) == 0


def test_acquire__after_full_release__builds_fresh_bundle():
    manager, created = _manager()
    config = _config()
    lease = manager.acquire(config, use_batching=True)
    lease.release(timeout=None)

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
            lease.release(timeout=None)

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
    bundle = connection_resources.SharedConnectionResourcesBundle(
        httpx_client=mock.Mock(),
        rest_client=mock.Mock(),
        message_processor=mock.Mock(),
        file_upload_manager=file_upload_manager,
        replay_manager=mock.Mock(),
        streamer=streamer,
        flush_timeout=flush_timeout,
    )
    return bundle, streamer, file_upload_manager


def test_bundle_close__flush_true__drains_streamer_and_upload_pool():
    bundle, streamer, file_upload_manager = _bundle_with_mock_transport()

    bundle.close(5, flush=True)

    # flush=True finalizes pending data: the streamer drains its queue and
    # flushes uploads, and the upload pool waits for in-flight uploads.
    streamer.close.assert_called_once_with(5, flush=True)
    file_upload_manager.close.assert_called_once_with(wait=True)


def test_bundle_close__flush_false__stops_without_waiting():
    bundle, streamer, file_upload_manager = _bundle_with_mock_transport()

    bundle.close(None, flush=False)

    streamer.close.assert_called_once_with(None, flush=False)
    file_upload_manager.close.assert_called_once_with(wait=False)
