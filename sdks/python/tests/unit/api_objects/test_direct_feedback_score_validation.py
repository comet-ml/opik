"""Direct ``log_feedback_score`` calls must validate before enqueueing.

``Opik.log_traces_feedback_scores``/``log_spans_feedback_scores`` filter invalid
scores through ``validation_helpers.validate_feedback_score``, but
``Trace.log_feedback_score``/``Span.log_feedback_score`` used to hand their
arguments straight to the streamer. An invalid ``metadata`` value then failed
inside ``jsonable_encoder`` on the background thread, outside the handled
``ApiError``/``ValidationError`` paths, so the score was dropped without a
usable error. These tests pin the validation to the calling thread.
"""

import pytest

from opik import config as opik_config
from opik.api_objects.span import span_client
from opik.api_objects.trace import trace_client

TRACE_ID = "01912f3a-0000-7000-8000-000000000001"
SPAN_ID = "01912f3a-0000-7000-8000-000000000002"


class FakeStreamer:
    def __init__(self):
        self.messages = []

    def put(self, message):
        self.messages.append(message)


@pytest.fixture
def streamer():
    return FakeStreamer()


@pytest.fixture
def trace(streamer):
    return trace_client.Trace(
        id=TRACE_ID,
        message_streamer=streamer,
        project_name="test-project",
        url_override="http://localhost:5173/api",
        source="sdk",
        config=opik_config.OpikConfig(),
    )


@pytest.fixture
def span(streamer):
    return span_client.Span(
        id=SPAN_ID,
        trace_id=TRACE_ID,
        project_name="test-project",
        message_streamer=streamer,
        url_override="http://localhost:5173/api",
        source="sdk",
    )


VALID_METADATA = [
    pytest.param(None, id="none"),
    pytest.param({}, id="empty"),
    pytest.param({"evaluator": "exact_match", "revision": "abc123"}, id="flat"),
    pytest.param({"outer": {"inner": [1, 2, 3]}}, id="nested"),
]

INVALID_METADATA = [
    pytest.param("not-a-dict", id="str"),
    pytest.param([("evaluator", "exact_match")], id="list"),
    pytest.param(42, id="int"),
]


@pytest.mark.parametrize("metadata", VALID_METADATA)
def test_trace_log_feedback_score__valid_metadata__message_enqueued(
    trace, streamer, metadata
):
    trace.log_feedback_score(name="quality", value=0.9, metadata=metadata)

    assert len(streamer.messages) == 1
    assert streamer.messages[0].batch[0].metadata == metadata


@pytest.mark.parametrize("metadata", INVALID_METADATA)
def test_trace_log_feedback_score__invalid_metadata__nothing_enqueued(
    trace, streamer, metadata
):
    trace.log_feedback_score(name="quality", value=0.9, metadata=metadata)

    assert streamer.messages == []


@pytest.mark.parametrize("metadata", VALID_METADATA)
def test_span_log_feedback_score__valid_metadata__message_enqueued(
    span, streamer, metadata
):
    span.log_feedback_score(name="quality", value=0.9, metadata=metadata)

    assert len(streamer.messages) == 1
    assert streamer.messages[0].batch[0].metadata == metadata


@pytest.mark.parametrize("metadata", INVALID_METADATA)
def test_span_log_feedback_score__invalid_metadata__nothing_enqueued(
    span, streamer, metadata
):
    span.log_feedback_score(name="quality", value=0.9, metadata=metadata)

    assert streamer.messages == []


def test_trace_log_feedback_score__invalid_value__nothing_enqueued(trace, streamer):
    trace.log_feedback_score(name="quality", value="not-a-number")

    assert streamer.messages == []


def test_span_log_feedback_score__invalid_value__nothing_enqueued(span, streamer):
    span.log_feedback_score(name="quality", value="not-a-number")

    assert streamer.messages == []
