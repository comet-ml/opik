"""
How events get onto the wire.

The collector takes one event per request and has no batch form, so the sender loops.
That makes its per-request error handling the interesting part: which answers it
carries on past, and which one answer ends reporting for the process.
"""

import pytest

from opik.analytics import comet_stats, worker as worker_module


def test_send__one_request_fails__the_rest_of_the_batch_still_goes():
    """
    An escaping exception would discard every event queued behind the failing one -
    and they are only ever reported once, so they would be lost for good.
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


@pytest.mark.parametrize("status", sorted(comet_stats.REJECTED_STATUSES))
def test_send__rejecting_status__raises_so_the_worker_can_stop(
    status, sender_answering, analytics_events
):
    """
    A definitive rejection is how the destination retires the SDK, or one version of
    it - it can tell versions apart from the `User-Agent` this client sends.
    """
    sender = sender_answering(status)

    with pytest.raises(worker_module.ReportingRejected):
        sender.send(analytics_events(5))

    # Gave up on the first answer rather than working through the batch.
    assert sender._client.requests == 1


@pytest.mark.parametrize("status", [429, 500, 502, 503])
def test_send__transient_status__keeps_going(
    status, sender_answering, analytics_events
):
    """`try later` is not `stop`; giving up here would lose events to a blip."""
    sender = sender_answering(status)

    sender.send(analytics_events(5))

    assert sender._client.requests == 5


def test_send__default_destination__tls_always_verified():
    """
    Deliberately not honouring `check_tls_certificate`: that setting is about
    reaching the user's own deployment, behind their own certificate, and this is a
    fixed public endpoint. Letting it apply would weaken a connection they never
    pointed at.
    """
    sender = comet_stats.Sender(url="https://collector.invalid/notify/event/")
    try:
        assert sender._client._transport._pool._ssl_context.verify_mode is not None
    finally:
        sender.close()
