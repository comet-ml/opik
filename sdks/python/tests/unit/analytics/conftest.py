import pytest

from opik.analytics import api, comet_stats, worker


class RecordingWorker:
    def __init__(self):
        self.events = []

    def enqueue(self, event):
        self.events.append(event)
        # The real worker returns whether it accepted the event; a falsy return
        # here would look like a full queue and release the caller's claim.
        return True

    @property
    def names(self):
        return [event.name for event in self.events]


@pytest.fixture
def recording_worker(monkeypatch):
    """Stands in for the background thread, so tests see what would be sent."""
    worker = RecordingWorker()
    monkeypatch.setattr(api, "_WORKER", worker)
    monkeypatch.setattr(api, "_DISABLED", False)
    # Reporting is once-per-process, and the process outlives a single test.
    monkeypatch.setattr(api, "_ALREADY_REPORTED", set())
    # Likewise the record of which functions report - left alone, a function
    # registered by one test would be treated as an outer call by the next.
    monkeypatch.setattr(api, "_REPORTING_CODE", set())
    return worker


@pytest.fixture
def analytics_events():
    """Builds a batch of events, for tests that only care how many there are."""

    def build(count):
        return [
            worker.Event(name=f"opik_python_sdk__client__method_{i}", properties={})
            for i in range(count)
        ]

    return build


@pytest.fixture
def sender_answering():
    """
    A real `Sender` with a stubbed HTTP client, so the loop and its error handling
    are the code under test rather than the transport.
    """

    def build(status):
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

    return build
