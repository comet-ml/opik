"""Unit tests for the SDK ``environment`` plumbing.

Covers:
- ``Opik.trace(environment=...)`` is the only entry point that accepts an
  explicit environment; the value flows into the emitted ``CreateTraceMessage``.
- Spans (``Trace.span``, ``Span.span``, ``@track``-created spans) inherit the
  parent trace's environment unconditionally.
- A nested ``@track(environment=...)`` whose value differs from the enclosing
  trace's environment logs a warning and is ignored — the parent trace's value
  wins (mirrors how mismatched ``project_name`` is handled).
- Backwards compat: existing call sites without ``environment`` still work.
"""

from unittest.mock import MagicMock

import opik
from opik.api_objects import opik_client
from opik.message_processing import messages
from opik import dict_utils


def _capture_messages(client: opik_client.Opik) -> MagicMock:
    mock_streamer = MagicMock()
    client._streamer = mock_streamer
    return mock_streamer


def _create_trace_messages(streamer: MagicMock):
    return [
        c.args[0]
        for c in streamer.put.call_args_list
        if isinstance(c.args[0], messages.CreateTraceMessage)
    ]


def _create_span_messages(streamer: MagicMock):
    return [
        c.args[0]
        for c in streamer.put.call_args_list
        if isinstance(c.args[0], messages.CreateSpanMessage)
    ]


def test_opik_client__no_environment_set__messages_have_none_environment_and_payload_omits_it():
    client = opik_client.Opik(project_name="test-project")
    streamer = _capture_messages(client)

    client.trace(name="t")
    client.span(name="s")

    trace_msg = _create_trace_messages(streamer)[0]
    span_msg = _create_span_messages(streamer)[0]

    assert trace_msg.environment is None
    assert span_msg.environment is None

    cleaned = dict_utils.remove_none_from_dict(trace_msg.as_payload_dict())
    assert "environment" not in cleaned


def test_opik_client_trace__environment_kwarg_propagates_to_create_trace_message():
    client = opik_client.Opik(project_name="test-project")
    streamer = _capture_messages(client)

    client.trace(name="t", environment="staging")

    assert _create_trace_messages(streamer)[0].environment == "staging"


def test_opik_client_trace__span_inherits_trace_environment():
    client = opik_client.Opik(project_name="test-project")
    streamer = _capture_messages(client)

    trace = client.trace(name="t", environment="staging")
    trace.span(name="s")

    assert _create_span_messages(streamer)[0].environment == "staging"


def test_opik_client_trace__nested_span_inherits_environment():
    client = opik_client.Opik(project_name="test-project")
    streamer = _capture_messages(client)

    trace = client.trace(name="t", environment="staging")
    span = trace.span(name="parent-span")
    span.span(name="child-span")

    span_msgs = _create_span_messages(streamer)
    assert len(span_msgs) == 2
    assert all(m.environment == "staging" for m in span_msgs)


def test_track_decorator__environment_propagates_to_root_trace_and_spans():
    client = opik_client.Opik(project_name="test-project")
    streamer = _capture_messages(client)
    opik_client.set_global_client(client)

    @opik.track(environment="staging")
    def my_fn():
        return 42

    my_fn()

    trace_msgs = _create_trace_messages(streamer)
    span_msgs = _create_span_messages(streamer)
    assert trace_msgs and span_msgs
    assert all(m.environment == "staging" for m in trace_msgs)
    assert all(m.environment == "staging" for m in span_msgs)


def test_track_decorator__nested_environment_mismatch_warns_and_inherits_parent(
    monkeypatch,
):
    from opik.api_objects import helpers as opik_helpers

    client = opik_client.Opik(project_name="test-project")
    streamer = _capture_messages(client)
    opik_client.set_global_client(client)

    warnings = []
    monkeypatch.setattr(
        opik_helpers.LOGGER,
        "warning",
        lambda msg, *a, **kw: warnings.append(msg % a if a else msg),
    )

    @opik.track(environment="this-should-be-ignored")
    def inner():
        return 1

    @opik.track(environment="staging")
    def outer():
        return inner()

    outer()

    span_msgs = _create_span_messages(streamer)
    assert span_msgs
    assert all(m.environment == "staging" for m in span_msgs)
    assert any("this-should-be-ignored" in w and "staging" in w for w in warnings), (
        warnings
    )


def test_create_trace_message__environment_field_default_is_none_for_backwards_compat():
    msg = messages.CreateTraceMessage(
        trace_id="t",
        project_name="p",
        name=None,
        start_time=None,  # type: ignore[arg-type]
        end_time=None,
        input=None,
        output=None,
        metadata=None,
        tags=None,
        error_info=None,
        thread_id=None,
        last_updated_at=None,
        source="sdk",
    )
    assert msg.environment is None


def test_create_environment__conflict_raises_environment_already_exists():
    from unittest.mock import patch
    from opik.exceptions import EnvironmentAlreadyExists
    from opik.rest_api.errors import ConflictError

    client = opik_client.Opik(project_name="test-project")

    with patch.object(
        client._rest_client.environments,
        "create_environment",
        side_effect=ConflictError(body={"message": "already exists"}),
    ):
        try:
            client.create_environment("production")
            assert False, "expected EnvironmentAlreadyExists"
        except EnvironmentAlreadyExists as e:
            assert "production" in str(e)


def test_create_span_message__environment_field_default_is_none_for_backwards_compat():
    msg = messages.CreateSpanMessage(
        span_id="s",
        trace_id="t",
        project_name="p",
        parent_span_id=None,
        name=None,
        start_time=None,  # type: ignore[arg-type]
        end_time=None,
        input=None,
        output=None,
        metadata=None,
        tags=None,
        type="general",
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
        last_updated_at=None,
        source="sdk",
    )
    assert msg.environment is None
