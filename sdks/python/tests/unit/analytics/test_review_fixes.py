"""Regression tests for the defects raised in review on PR #7959."""

import pytest

from opik import analytics, environment_details
from opik.analytics import api, comet_stats, worker as worker_module


def test_build_event_name__runtime_segment_with_the_separator__stays_splittable():
    """
    Every call site passes a literal and a test keeps those separator-free, but a
    segment built at runtime would otherwise add levels that were never intended.
    """
    name = api._build_event_name("client", ("create__dataset",))

    assert name == "opik_python_sdk__client__create_dataset"
    assert len(name.split("__")) == 3


def test_send__one_request_fails__the_rest_of_the_batch_still_goes(monkeypatch):
    """
    The collector takes one event per request, so an escaping exception would discard
    every event queued behind the failing one - and they are only reported once.
    """
    attempted = []

    class FlakyClient:
        def post(self, url, **kwargs):
            name = kwargs["json"]["event_type"]
            attempted.append(name)
            if name.endswith("second"):
                raise RuntimeError("network blip")
            return type("Response", (), {"status_code": 201})()

    sender = comet_stats.Sender.__new__(comet_stats.Sender)
    sender._url = "http://collector.invalid"
    sender._client = FlakyClient()

    sender.send(
        [
            worker_module.Event(
                name=f"opik_python_sdk__client__{action}", properties={}
            )
            for action in ("first", "second", "third")
        ]
    )

    assert [name.split("__")[-1] for name in attempted] == ["first", "second", "third"]


def test_track_event__queue_full__event_not_left_claimed(recording_worker, monkeypatch):
    """
    Claiming happens before the hand-off. If the hand-off is refused the claim has to
    go with it, or the event is lost for the rest of the process.
    """

    class FullWorker:
        def enqueue(self, event):
            return False

    monkeypatch.setattr(api, "_WORKER", FullWorker())
    analytics.track_event("client", "create_dataset")

    assert api._ALREADY_REPORTED == set()

    # A later call, once the queue has drained, still reports it.
    monkeypatch.setattr(api, "_WORKER", recording_worker)
    analytics.track_event("client", "create_dataset")

    assert recording_worker.names == ["opik_python_sdk__client__create_dataset"]


def test_start_worker__no_destination_configured__no_thread_started(monkeypatch):
    """An empty URL would make every batch fail; do not start a thread to produce them."""
    monkeypatch.setattr(api, "_WORKER", None)
    monkeypatch.setattr(api, "_DISABLED", False)
    monkeypatch.setattr(api.rules, "reporting_allowed", lambda config_: True)
    monkeypatch.setattr(
        api.config,
        "OpikConfig",
        lambda **_: type(
            "C", (), {"analytics_url": "", "check_tls_certificate": True}
        )(),
    )

    assert api._start_worker() is None
    assert api._WORKER is None
    assert api._DISABLED is True


@pytest.fixture
def fresh_context():
    environment_details.collect_context_once.cache_clear()
    worker_module.session_properties.cache_clear()
    yield
    environment_details.collect_context_once.cache_clear()
    worker_module.session_properties.cache_clear()


def test_reset_after_fork__session_properties_rebuilt(fresh_context):
    """
    `pid` and `session_id` describe one process. Left cached, a forked child reports
    under its parent's identity and the two cannot be told apart downstream.
    """
    before = dict(worker_module.session_properties())

    environment_details._reset_after_fork()
    api._reset_after_fork()

    after = worker_module.session_properties()

    assert after["session_id"] != before["session_id"]
    assert after["pid"] == before["pid"]  # same process here; the id is what proves it
