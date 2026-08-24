"""
The destination can retire the SDK, or one version of it, by answering with a
rejecting status — it can tell versions apart from the `User-Agent` this client
sends. That only stops the traffic if the SDK takes the answer seriously, which is
what these cover.
"""

import pytest

from opik.analytics import comet_stats, worker as worker_module


def _sender_answering(status):
    class Client:
        def __init__(self):
            self.requests = 0

        def post(self, url, **kwargs):
            self.requests += 1
            return type("Response", (), {"status_code": status})()

    sender = comet_stats.Sender.__new__(comet_stats.Sender)
    sender._url = "http://collector.invalid"
    sender._client = Client()
    return sender


def _events(count):
    return [
        worker_module.Event(name=f"opik_python_sdk__client__method_{i}", properties={})
        for i in range(count)
    ]


@pytest.mark.parametrize("status", sorted(comet_stats.REJECTED_STATUSES))
def test_send__rejecting_status__raises_so_the_worker_can_stop(status):
    sender = _sender_answering(status)

    with pytest.raises(worker_module.ReportingRejected):
        sender.send(_events(5))

    # Gave up on the first answer rather than working through the batch.
    assert sender._client.requests == 1


@pytest.mark.parametrize("status", [429, 500, 502, 503])
def test_send__transient_status__keeps_going(status):
    """`try later` is not `stop`; giving up here would lose events to a blip."""
    sender = _sender_answering(status)

    sender.send(_events(5))

    assert sender._client.requests == 5


def test_worker__rejected__stops_and_tells_the_caller_side():
    stopped = []

    def send(events):
        raise worker_module.ReportingRejected("destination answered 410")

    worker = worker_module.Worker(
        send=send,
        max_queue_size=100,
        max_batch_size=1,
        batch_timeout_seconds=0.05,
        on_rejected=lambda: stopped.append(True),
    )
    worker.start()
    try:
        worker.enqueue(_events(1)[0])
        worker.flush(timeout=5)

        assert stopped == [True]
    finally:
        worker.close(timeout=5)


def test_worker__ordinary_failure__keeps_running():
    stopped = []

    def send(events):
        raise RuntimeError("network blip")

    worker = worker_module.Worker(
        send=send,
        max_queue_size=100,
        max_batch_size=1,
        batch_timeout_seconds=0.05,
        on_rejected=lambda: stopped.append(True),
    )
    worker.start()
    try:
        worker.enqueue(_events(1)[0])
        worker.flush(timeout=5)

        assert stopped == []
        assert worker.is_alive()
    finally:
        worker.close(timeout=5)
