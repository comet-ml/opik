"""
What `client__init` and `client__track` are each supposed to count.

`client__init` means the user built a client. The SDK builds one for itself in
`get_global_client`, and that lives in the same module as `Opik.__init__`, so the
module test in `_reported_from_inside_the_sdk` cannot tell the two apart - which is
why `get_global_client` is marked `@analytics.internal`. Without it a bare `@track`
function reports `client__init` and the event counts everyone who touches the SDK.

`client__track` is the other half: the decorator is the SDK's most-used entry point
and had no event of its own, so nothing counted the people using it.
"""

import types

import pytest

from opik.analytics import api


def test_internal__marked_caller__callee_does_not_report(recording_worker):
    """The marker's whole job: what it calls is Opik using itself."""

    def reports():
        api.track_event("client", "init")

    @api.internal
    def sdk_builds_one_for_itself():
        reports()

    def user_code():
        sdk_builds_one_for_itself()

    user_code()

    assert recording_worker.names == []


def test_internal__unmarked_caller__callee_reports(recording_worker):
    """The control: without the marker the same shape is a user's own call."""

    def reports():
        api.track_event("client", "init")

    def user_builds_one():
        reports()

    user_builds_one()

    assert recording_worker.names == ["opik_python_sdk__client__init"]


def test_track_decorator__used_by_a_user__reports_client_track(recording_worker):
    import opik

    @opik.track
    def a_function_the_user_wrote(x):
        return x + 1

    # Called, not just decorated: a decorator that reported the event and then
    # returned a broken wrapper would otherwise pass this.
    assert a_function_the_user_wrote(1) == 2
    assert "opik_python_sdk__client__track" in recording_worker.names


def test_track_decorator__used_by_an_integration__does_not_report(recording_worker):
    """
    Integrations decorate on the user's behalf from inside `opik.integrations.*`, so
    their use of the decorator is Opik's, not a user reaching for `@track`.
    """

    entrypoint_module = types.ModuleType("opik.integrations.fake.opik_tracker")
    exec(
        compile(
            "def track_fake(fn):\n    import opik\n    return opik.track(fn)\n",
            "opik/integrations/fake/opik_tracker.py",
            "exec",
        ),
        entrypoint_module.__dict__,
    )

    def user_enables_an_integration():
        entrypoint_module.track_fake(lambda: None)

    user_enables_an_integration()

    assert "opik_python_sdk__client__track" not in recording_worker.names


def test_get_global_client__builds_a_client_for_the_sdk__does_not_report_init(
    recording_worker, monkeypatch
):
    """
    The real path, not a stand-in for it: `get_global_client` is what `@track` and
    every other implicit consumer reach for, and the marker on it is the only thing
    keeping `client__init` from counting them.
    """
    from opik.api_objects import opik_client

    monkeypatch.setattr(opik_client, "_global_singleton", None)

    def user_code():
        opik_client.get_global_client()

    user_code()

    assert "opik_python_sdk__client__init" not in recording_worker.names


def test_metric_created__construction_fails__still_reported(recording_worker):
    """
    Reporting goes on the first line of the function it reports on, so a call that
    goes on to fail still counts as usage - the rule the instrumentation skill
    documents. `BaseMetric` reported last, so a metric rejected by its own
    validation went uncounted even though the user clearly reached for it.
    """
    from opik.evaluation.metrics import base_metric

    class Scored(base_metric.BaseMetric):
        def score(self, *args, **kwargs):
            return None

    def user_code():
        with pytest.raises(ValueError):
            # project_name is only allowed when track is on
            Scored(name="x", track=False, project_name="rejected")

    user_code()

    assert "opik_python_sdk__evaluation__metric_created" in recording_worker.names


def test_metric_created__subclass_forging_an_opik_module__reported_as_custom(
    recording_worker,
):
    """
    The payload must never carry a name the user chose. `__module__` is writable, so
    trusting it would let any subclass have its own class name reported as one of
    Opik's - including by claiming a module that really exists.
    """
    from opik.evaluation.metrics import base_metric

    class ANameTheUserChose(base_metric.BaseMetric):
        def score(self, *args, **kwargs):
            return None

    for forged in ("opik.user_metrics", "opik.evaluation.metrics.base_metric"):
        ANameTheUserChose.__module__ = forged
        assert not base_metric._is_opik_metric(ANameTheUserChose)

    def user_code():
        ANameTheUserChose(track=False)

    user_code()

    assert recording_worker.events
    metric_events = [
        event for event in recording_worker.events if "metric_created" in event.name
    ]
    assert metric_events
    for event in metric_events:
        assert event.properties["metric"] == "custom"


def test_metric_created__opik_own_metric__reported_by_name(recording_worker):
    """The other side: a real one still has to be identifiable."""
    from opik.evaluation.metrics import Equals

    def user_code():
        Equals()

    user_code()

    metric_events = [
        event for event in recording_worker.events if "metric_created" in event.name
    ]
    assert metric_events
    assert metric_events[0].properties["metric"] == "Equals"
