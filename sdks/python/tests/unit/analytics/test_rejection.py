"""
What the SDK does once the destination has turned it down.

`test_sender.py` covers which answers count as a rejection; these cover the reaction
to one - the worker thread stopping, and reporting staying off for the rest of the
process. A rejection is only a kill switch if it actually stops the traffic.
"""

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from opik import config
from opik.analytics import api, worker as worker_module


def test_worker__rejected__stops_and_tells_the_caller_side(analytics_events):
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
        worker.enqueue(analytics_events(1)[0])
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
        worker.enqueue(analytics_events(1)[0])
        worker.flush(timeout=2)
        assert attempted == []
    finally:
        worker.close(timeout=5)


def test_worker__ordinary_failure__keeps_running(analytics_events):
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
        worker.enqueue(analytics_events(1)[0])
        worker.flush(timeout=5)

        assert stopped == []
        assert worker.is_alive()
    finally:
        worker.close(timeout=5)


@pytest.fixture
def rejecting_collector():
    """
    A collector that turns everything down, torn down completely: `shutdown()` alone
    stops `serve_forever` but leaves the listening socket and its thread behind, so a
    failing assertion here would leak both into every test that runs afterwards.
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
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}/notify/event/", requests
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def test_track_event__destination_rejects__whole_process_stops_reporting(
    rejecting_collector, monkeypatch
):
    """
    The end-to-end path, through the real composition `_start_worker` builds rather
    than a stand-in worker: a rejection has to stop `track_event` itself, not just
    the pieces underneath it. Nothing here would notice `on_rejected` being wired
    up wrongly, or not at all, if the other tests were the only ones.
    """
    url, requests = rejecting_collector

    # Reporting is off under pytest by design, so the rule is stubbed rather than
    # the machinery: everything below `track_event` stays the real thing.
    monkeypatch.setattr(api.rules, "reporting_allowed", lambda config_: True)
    monkeypatch.setattr(api, "_WORKER", None)
    monkeypatch.setattr(api, "_SENDER", None)
    monkeypatch.setattr(api, "_DISABLED", False)
    monkeypatch.setattr(api, "_ALREADY_REPORTED", set())
    monkeypatch.setattr(api, "_REPORTING_CODE", set())
    # A real config with the destination overridden, not a stand-in object: this
    # replaces `OpikConfig` for the whole SDK, and the worker thread reads it too
    # when it builds the session properties. A stub with only the fields
    # `_start_worker` happens to touch fails there instead, and the failure is
    # swallowed - which looked like "the event vanished".
    real_config = config.OpikConfig
    monkeypatch.setattr(
        api.config, "OpikConfig", lambda **_: real_config(analytics_url=url)
    )

    try:
        api.track_event("client", "first")
        api.flush(timeout=10)

        assert requests == ["opik_python_sdk__client__first"]
        assert api._DISABLED is True
        # The rejection releases the connection pool rather than leaving it open
        # for a process that will never send again.
        assert api._SENDER is None

        # A later event must cost nothing: no request, and nothing queued either.
        for i in range(10):
            api.track_event("client", f"after_{i}")
        api.flush(timeout=5)

        assert requests == ["opik_python_sdk__client__first"]
    finally:
        api.shutdown(timeout=5)
