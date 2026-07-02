"""Sharing behaviour of Opik clients over ref-counted connection resources.

These assert only through the public client surface (`rest_client`, `trace()`,
`flush()`, `end()`): clients with a matching connection config reuse one
transport, distinct configs get their own, and ending one client does not
disable another that shares the connection. The ref-counting/eviction mechanics
themselves are covered at the manager level in
``test_connection_resource_manager.py``.
"""

import gc
import threading
import time
import weakref
from unittest import mock

from opik.api_objects import opik_client


def _make_client(**kwargs) -> opik_client.Opik:
    return opik_client.Opik(_show_misconfiguration_message=False, **kwargs)


def test_get_global_client__concurrent_cold_start__creates_single_client():
    # Regression: get_global_client() must create the singleton once under
    # concurrency. When several threads hit the cold-start path together (e.g. a
    # tracer's _opik_client property accessed from parallel pipelines), each
    # building its own client would race the shared connection-resource manager
    # and, under a streamer-sharing test backend, close a streamer still in use —
    # hanging a later flush().
    opik_client.reset_global_client(end_client=False)

    thread_count = 8
    barrier = threading.Barrier(thread_count)
    constructed = []
    results = []
    results_lock = threading.Lock()

    def slow_construct(*args, **kwargs):
        time.sleep(0.02)  # widen the window a racy implementation would lose in
        client = object()
        constructed.append(client)
        return client

    def worker() -> None:
        barrier.wait()  # release all threads into the cold-start path together
        client = opik_client.get_global_client()
        with results_lock:
            results.append(client)

    try:
        with mock.patch.object(opik_client, "Opik", side_effect=slow_construct):
            threads = [threading.Thread(target=worker) for _ in range(thread_count)]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join()

        assert len(constructed) == 1  # built exactly once despite the race
        assert {id(client) for client in results} == {id(constructed[0])}
    finally:
        opik_client.reset_global_client(end_client=False)


def test_opik_clients__matching_connection_config__share_one_rest_client():
    client_a = _make_client()
    client_b = _make_client()
    try:
        # A shared connection is observable through the public REST client:
        # both handles expose the very same underlying client object.
        assert client_a.rest_client is client_b.rest_client
    finally:
        client_a.end(flush=False)
        client_b.end(flush=False)


def test_opik_clients__distinct_connection_config__use_separate_rest_clients():
    client_a = _make_client()
    client_b = _make_client(host="http://localhost:39999/api")
    try:
        assert client_a.rest_client is not client_b.rest_client
    finally:
        client_a.end(flush=False)
        client_b.end(flush=False)


def test_opik_client__logs_after_co_located_client_ended__data_still_delivered(
    fake_backend,
):
    keeper = _make_client()
    transient = _make_client()  # shares keeper's connection

    # Ending one client releases only its reference; the shared transport must
    # stay alive for the other handle.
    transient.end(flush=False)

    keeper.trace(name="after-sibling-end")
    keeper.flush()

    assert [trace.name for trace in fake_backend.trace_trees] == ["after-sibling-end"]

    keeper.end(flush=False)


def test_opik_client__dropped_without_end__is_garbage_collected(fake_backend):
    # No lingering strong reference (a cached manager entry, the GC finalizer, a
    # background thread, or the global singleton) should keep a dropped client
    # alive. After `del` + `gc.collect()`, its weakref must no longer resolve.
    # A unique host gives this handle its own isolated bundle so the assertion is
    # about this client alone.
    opik_client.reset_global_client(end_client=False)

    client = _make_client(host="http://localhost:39998/api")
    client_ref = weakref.ref(client)

    del client
    gc.collect()

    assert client_ref() is None
