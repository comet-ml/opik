import pytest

from opik.analytics import api


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
