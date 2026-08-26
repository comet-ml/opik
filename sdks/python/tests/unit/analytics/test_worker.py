import threading
import time

import pytest

from opik.analytics import worker as worker_module
from opik import environment_details


def _event(name="opik_test_event", **properties):
    return worker_module.Event(name=name, properties=properties)


@pytest.fixture
def worker_factory():
    started = []

    def factory(send):
        worker = worker_module.Worker(
            send=send,
            max_queue_size=100,
            max_batch_size=1000,
            batch_timeout_seconds=0.05,
        )
        worker.start()
        started.append(worker)
        return worker

    yield factory

    for worker in started:
        worker.close(timeout=5)


def test_worker__enqueued_events__sent(worker_factory):
    sent = []
    worker = worker_factory(sent.extend)

    worker.enqueue(_event())
    assert worker.flush(timeout=5)

    assert [event.name for event in sent] == ["opik_test_event"]


def test_worker__session_properties_added(worker_factory):
    sent = []
    worker = worker_factory(sent.extend)

    worker.enqueue(_event(count=1))
    assert worker.flush(timeout=5)

    properties = sent[0].properties
    assert properties["count"] == 1
    assert properties["sdk_language"] == "python"
    assert properties["release"]
    assert properties["session_id"]


def test_worker__session_properties__match_the_ones_reported_to_sentry(worker_factory):
    """
    The two payloads are meant to describe the same run, so both read the same
    `environment_details` collectors.
    """
    sent = []
    worker = worker_factory(sent.extend)

    worker.enqueue(_event())
    assert worker.flush(timeout=5)

    sentry_properties = {
        **environment_details.collect_tags_once(),
        **environment_details.collect_context_once(),
    }
    reported = sent[0].properties

    assert sentry_properties.items() <= reported.items()
    assert reported["session_id"] == sentry_properties["session_id"]


def test_worker__send_raises__caller_unaffected(worker_factory):
    def failing_send(events):
        raise ValueError("boom")

    worker = worker_factory(failing_send)

    worker.enqueue(_event())
    assert worker.flush(timeout=5)


def test_worker__sending__happens_on_a_background_thread(worker_factory):
    sending_threads = []
    worker = worker_factory(
        lambda events: sending_threads.append(threading.current_thread())
    )

    worker.enqueue(_event())
    assert worker.flush(timeout=5)

    assert sending_threads
    assert threading.current_thread() not in sending_threads


def test_worker__send_blocks__enqueue_does_not(worker_factory):
    release = threading.Event()
    worker = worker_factory(lambda events: release.wait(timeout=10))

    # The first event occupies the worker thread inside `send`, so every
    # subsequent `enqueue` has to return without waiting for it.
    worker.enqueue(_event(name="opik_blocking_event"))

    started_at = time.monotonic()
    for _ in range(50):
        worker.enqueue(_event())
    elapsed = time.monotonic() - started_at

    release.set()
    assert elapsed < 1.0


def test_worker__queue_full__events_dropped_instead_of_blocking():
    release = threading.Event()
    worker = worker_module.Worker(
        send=lambda events: release.wait(timeout=10),
        max_queue_size=5,
        max_batch_size=1,
        batch_timeout_seconds=0.05,
    )
    worker.start()
    try:
        started_at = time.monotonic()
        for _ in range(100):
            worker.enqueue(_event())
        assert time.monotonic() - started_at < 1.0
    finally:
        release.set()
        worker.close(timeout=5)
