"""
The destination can retire the SDK, or one version of it, by answering with a
rejecting status — it can tell versions apart from the `User-Agent` this client
sends. That only stops the traffic if the SDK takes the answer seriously, which is
what these cover.
"""

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from opik.analytics import api, comet_stats, worker as worker_module


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
    attempted = []

    def send(events):
        attempted.append(events)
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

        # The thread has to actually stop, not just report that it should: one that
        # kept draining the queue would satisfy the callback assertion alone.
        deadline = time.time() + 5
        while worker.is_alive() and time.time() < deadline:
            time.sleep(0.01)
        assert not worker.is_alive()

        # ... and nothing queued afterwards is sent.
        attempted.clear()
        worker.enqueue(_events(1)[0])
        worker.flush(timeout=2)
        assert attempted == []
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


def test_track_event__destination_rejects__whole_process_stops_reporting(monkeypatch):
    """
    The end-to-end path, through the real composition `_start_worker` builds rather
    than a stand-in worker: a rejection has to stop `track_event` itself, not just
    the pieces underneath it. Nothing here would notice `on_rejected` being wired
    up wrongly, or not at all, if the other tests were the only ones.
    """
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            body = self.rfile.read(int(self.headers["Content-Length"]))
            requests.append(json.loads(body)["event_type"])
            self.send_response(410)
            self.end_headers()

        def log_message(self, *args):
            pass

    server = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    url = f"http://127.0.0.1:{server.server_address[1]}/notify/event/"

    # Reporting is off under pytest by design, so the rule is stubbed rather than
    # the machinery: everything below `track_event` stays the real thing.
    monkeypatch.setattr(api.rules, "reporting_allowed", lambda config_: True)
    monkeypatch.setattr(api, "_WORKER", None)
    monkeypatch.setattr(api, "_DISABLED", False)
    monkeypatch.setattr(api, "_ALREADY_REPORTED", set())
    monkeypatch.setattr(api, "_REPORTING_CODE", set())
    monkeypatch.setattr(
        api.config,
        "OpikConfig",
        lambda **_: type(
            "C", (), {"analytics_url": url, "check_tls_certificate": True}
        )(),
    )

    try:
        api.track_event("client", "first")
        api.flush(timeout=10)

        assert requests == ["opik_python_sdk__client__first"]
        assert api._DISABLED is True

        # A later event must cost nothing: no request, and nothing queued either.
        for i in range(10):
            api.track_event("client", f"after_{i}")
        api.flush(timeout=5)

        assert requests == ["opik_python_sdk__client__first"]
    finally:
        api.shutdown(timeout=5)
        server.shutdown()
