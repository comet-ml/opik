"""
What each event is supposed to count.

The recurring failure mode is an event that counts Opik rather than the user. Two
mechanisms keep it honest, and these cover both:

- `@analytics.internal` marks a function whose callees are Opik using itself. It is
  needed where the module test in `_reported_from_inside_the_sdk` cannot help - an
  internal caller living in the same module as the thing it calls looks exactly like
  a user calling it.
- `BaseMetric` reports through a private helper in its own module, the case that must
  keep working: a helper reporting on its caller's behalf is not an internal call.
- `@analytics.entry_point` is the exemption: a whole flow one command hands to
  another is still the user's own, and without it the handover reports nothing.
"""

import types

import pytest

import opik
from opik.analytics import api


def test_internal__marked_caller__callee_does_not_report(recording_worker):
    """The marker's whole job: what it calls is Opik using itself."""

    def reports():
        api.track_event("client", "create_dataset")

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
        api.track_event("client", "create_dataset")

    def user_creates_one():
        reports()

    user_creates_one()

    assert recording_worker.names == ["opik_python_sdk__client__create_dataset"]


def test_feedback_scores__carried_by_a_new_trace__not_reported(
    recording_worker, fake_backend
):
    """
    `client__log_traces_feedback_scores` must mean the user called that method.

    `__internal_api__trace__` hands scores supplied at creation to
    `log_traces_feedback_scores`, and it is the trace-creation path behind `@track`,
    every integration tracer, `opik_context` and the evaluation engine. Both live in
    `opik_client`, so the module test cannot separate them - without the marker on the
    internal API, every integration that attaches a score reports this event and it
    counts Opik's plumbing instead of anyone's usage.
    """
    client = opik.Opik(batching=True)

    def user_code():
        client.trace(name="t", feedback_scores=[{"name": "s", "value": 1.0}])

    user_code()

    assert (
        "opik_python_sdk__client__log_traces_feedback_scores"
        not in recording_worker.names
    )


def test_feedback_scores__carried_by_a_new_span__not_reported(
    recording_worker, fake_backend
):
    """The same, through `__internal_api__span__`."""
    client = opik.Opik(batching=True)
    trace = client.trace(name="t")

    def user_code():
        client.span(
            trace_id=trace.id,
            name="s",
            feedback_scores=[{"name": "s", "value": 1.0}],
        )

    user_code()

    assert (
        "opik_python_sdk__client__log_spans_feedback_scores"
        not in recording_worker.names
    )


def test_feedback_scores__logged_by_the_user__reported(recording_worker, fake_backend):
    """
    The other side, and the reason the marker goes on the internal API rather than on
    the reporting method: a direct call still has to count.
    """
    client = opik.Opik(batching=True)
    trace = client.trace(name="t")

    def user_code():
        client.log_traces_feedback_scores([{"id": trace.id, "name": "s", "value": 1.0}])

    user_code()

    assert (
        "opik_python_sdk__client__log_traces_feedback_scores" in recording_worker.names
    )


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


def _in_module(function, module_name):
    """The same function, reported as living in `module_name`.

    The module test in `_reported_from_inside_the_sdk` reads `__name__` off the
    frame's globals, and a test module is not an `opik` one - so a caller that
    looks like the SDK has to be built rather than imported.
    """
    globals_ = dict(function.__globals__)
    globals_["__name__"] = module_name
    return types.FunctionType(
        function.__code__,
        globals_,
        function.__name__,
        function.__defaults__,
        function.__closure__,
    )


def test_entry_point__handed_over_by_another_opik_module__still_reports(
    recording_worker,
):
    """`opik configure` calling `opik mcp configure` is the user's own flow.

    Without the marker this is indistinguishable from an internal call, and the
    whole redirect - the path most people take to MCP setup - reports nothing.
    """

    @api.entry_point
    def run_configure():
        api.track_event("configuration", "mcp_configure")

    handing_over = _in_module(lambda: run_configure(), "opik.cli.configure")

    handing_over()

    assert recording_worker.names == ["opik_python_sdk__configuration__mcp_configure"]


def test_entry_point__unmarked__handover_is_dropped(recording_worker):
    """The control, and the bug it exists to fix."""

    def run_configure():
        api.track_event("configuration", "mcp_configure")

    handing_over = _in_module(lambda: run_configure(), "opik.cli.configure")

    handing_over()

    assert recording_worker.names == []


def test_entry_point__nested_call_below_it__still_dropped(recording_worker):
    """The exemption covers the marked frame only, not everything beneath it."""

    def reports_from_deeper():
        api.track_event("client", "create_dataset")

    @api.entry_point
    def run_configure():
        api.track_event("configuration", "mcp_configure")
        reports_from_deeper()

    run_configure()

    assert recording_worker.names == ["opik_python_sdk__configuration__mcp_configure"]
