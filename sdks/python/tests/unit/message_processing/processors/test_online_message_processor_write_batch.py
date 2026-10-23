"""Span and trace batches are sent as a prepared body rather than through the generated client.

The gate on that change is that the request is unchanged: same route, method, headers and
JSON, so each body is compared, parsed, against the one the generated client sends.
"""

import datetime
import gzip
import json
from typing import Any, List
from unittest import mock

import pytest

from opik import httpx_client
from opik.message_processing import messages
from opik.message_processing.processors import online_message_processor
from opik.rest_api import client as rest_api_client, core as rest_api_core
from opik.rest_api.types import error_info_write, span_write, trace_write

_BASE_URL = "http://opik-write-batch-tests.local"
START_TIME = datetime.datetime(
    2026, 10, 1, 12, 0, 0, 123456, tzinfo=datetime.timezone.utc
)


def _read_body(request: Any) -> Any:
    body = request.read()
    if body[:2] == b"\x1f\x8b":
        body = gzip.decompress(body)
    return json.loads(body)


def _rest_client(compress: bool = False) -> rest_api_client.OpikApi:
    return rest_api_client.OpikApi(
        base_url=_BASE_URL,
        api_key="api-key",
        workspace_name="workspace",
        httpx_client=httpx_client.OpikHttpxClient(compress_json_requests=compress),
    )


def _processor(rest_client: Any) -> online_message_processor.OpikMessageProcessor:
    return online_message_processor.OpikMessageProcessor(
        rest_client=rest_client,
        file_upload_manager=mock.MagicMock(),
        fallback_replay_manager=mock.MagicMock(),
        unauthorized_message_types_registry=mock.MagicMock(),
        data_loss_tracker=mock.MagicMock(),
    )


def _spans() -> List[span_write.SpanWrite]:
    return [
        span_write.SpanWrite(
            id="span-1",
            project_name="a-project",
            trace_id="trace-1",
            parent_span_id="parent-1",
            name="llm_call",
            type="llm",
            start_time=START_TIME,
            end_time=START_TIME + datetime.timedelta(seconds=1),
            input={"messages": [{"role": "user", "content": "réponse 🙂"}]},
            output={"answer": "a", "nested": {"x": [1, 2.5, None, True]}},
            metadata={"temperature": 0.0, "n": 3},
            model="gpt-4o-mini",
            provider="openai",
            tags=["a", "b"],
            usage={"prompt_tokens": 412, "completion_tokens": 128},
            error_info=error_info_write.ErrorInfoWrite(
                exception_type="ValueError", message="boom", traceback="tb"
            ),
            last_updated_at=START_TIME,
            total_estimated_cost=0.0012,
        ),
        # Set to None explicitly: must go out as null, while unset fields are omitted.
        span_write.SpanWrite(id="span-2", start_time=START_TIME, end_time=None),
    ]


def _traces() -> List[trace_write.TraceWrite]:
    return [
        trace_write.TraceWrite(
            id="trace-1",
            project_name="a-project",
            name="agent_run",
            start_time=START_TIME,
            end_time=START_TIME + datetime.timedelta(seconds=2),
            input={"question": "q"},
            output={"answer": "a"},
            metadata={"k": "v"},
            tags=["t"],
            thread_id="thread-1",
            last_updated_at=START_TIME,
        ),
        trace_write.TraceWrite(id="trace-2", start_time=START_TIME, end_time=None),
    ]


_CASES = [
    (
        "spans",
        _spans,
        "_process_create_spans_batch_message",
        messages.CreateSpansBatchMessage,
    ),
    (
        "traces",
        _traces,
        "_process_create_traces_batch_message",
        messages.CreateTraceBatchMessage,
    ),
]


@pytest.mark.parametrize("key, make_batch, handler, message_class", _CASES)
def test_write_batch__prepared_body__matches_the_generated_client(
    respx_mock: Any, key: str, make_batch: Any, handler: str, message_class: Any
) -> None:
    route = respx_mock.post(url__regex=rf".*/v1/private/{key}/batch").respond(204)
    rest_client = _rest_client()

    getattr(_processor(rest_client), handler)(message_class(batch=make_batch()))
    assert len(route.calls) == 1
    prepared = _read_body(route.calls[0].request)

    route.reset()
    getattr(getattr(rest_client, key), f"create_{key}")(**{key: make_batch()})
    assert len(route.calls) == 1
    generated = _read_body(route.calls[0].request)

    assert prepared == generated
    assert "end_time" in prepared[key][1] and prepared[key][1]["end_time"] is None
    assert "name" not in prepared[key][1]


@pytest.mark.parametrize("key, make_batch, handler, message_class", _CASES)
def test_write_batch__framing__post_with_auth_and_gzip(
    respx_mock: Any, key: str, make_batch: Any, handler: str, message_class: Any
) -> None:
    route = respx_mock.post(url__regex=rf".*/v1/private/{key}/batch").respond(204)

    getattr(_processor(_rest_client(compress=True)), handler)(
        message_class(batch=make_batch())
    )

    request = route.calls[0].request
    assert request.method == "POST"
    assert request.url.path.endswith(f"/v1/private/{key}/batch")
    assert request.headers["content-encoding"] == "gzip"
    assert request.content[:2] == b"\x1f\x8b"
    assert request.headers["authorization"] == "api-key"
    assert request.headers["comet-workspace"] == "workspace"


def test_write_batch__error_response__raises_api_error_with_status_and_headers(
    respx_mock: Any,
) -> None:
    respx_mock.post(url__regex=r".*/v1/private/spans/batch").respond(
        409, json={"errors": ["conflict"]}, headers={"x-test": "1"}
    )

    with pytest.raises(rest_api_core.ApiError) as raised:
        _processor(_rest_client())._send_write_batch(
            "v1/private/spans/batch", "spans", _spans()
        )

    assert raised.value.status_code == 409
    assert raised.value.headers["x-test"] == "1"
    assert raised.value.body == {"errors": ["conflict"]}


@pytest.mark.parametrize(
    "make_batch, message_class",
    [
        (_spans, messages.CreateSpansBatchMessage),
        (_traces, messages.CreateTraceBatchMessage),
    ],
)
def test_write_batch_message__db_dict__matches_generated_dict(
    make_batch: Any, message_class: Any
) -> None:
    """The replay store keeps what `.dict()` produced, without the introspecting pass."""
    batch = make_batch()
    message = message_class(batch=batch)

    assert message.as_db_message_dict()["batch"] == [item.dict() for item in batch]
