import json
import logging
from unittest import mock

import pytest

from opik.api_objects import search_helpers
from opik.rest_api.core.api_error import ApiError
from opik.rest_api.types import span_public, trace_public


SPAN_RECORD = {
    "id": "0195f6f1-9da2-7630-b285-60cf5580372f",
    "project_id": "0195f6f1-9c82-751f-a1ad-54fec4b5c7d8",
    "trace_id": "0195f6f1-9c3f-7b65-a334-56173d19bc00",
    "name": "synthesize",
    "type": "general",
    "start_time": "2025-01-03T11:03:17.875608Z",
    "end_time": "2025-01-03T11:03:18.591814Z",
    "input": {"q": "x"},
    "output": {"a": "y"},
    "created_at": "2025-04-02T14:39:44.412550Z",
    "last_updated_at": "2025-04-02T14:39:44.412550Z",
    "created_by": "admin",
    "last_updated_by": "admin",
    "duration": 1.0,
}

TRACE_RECORD = {
    "id": "0195f6f1-9c3f-7b65-a334-56173d19bc00",
    "project_id": "0195f6f1-9c82-751f-a1ad-54fec4b5c7d8",
    "name": "root",
    "start_time": "2025-01-03T11:03:17.505783Z",
    "end_time": "2025-01-03T11:03:18.591897Z",
    "input": {"q": "x"},
    "output": {"a": "y"},
    "created_at": "2025-04-02T14:39:44.412550Z",
    "last_updated_at": "2025-04-02T14:39:44.412550Z",
    "created_by": "admin",
    "last_updated_by": "admin",
    "duration": 1.0,
}


def _stream(record: dict):
    """Mimic the bytes-iterator returned by the Fern-generated search clients."""
    yield f"{json.dumps(record)}\r\n".encode("utf-8")


@pytest.fixture
def patched_sleep():
    with mock.patch("opik.api_objects.rest_helpers.time.sleep") as sleep_mock:
        yield sleep_mock


def _sleep_called_with(mock_sleep: mock.Mock, seconds) -> bool:
    """Patching time.sleep is global — background threads also trip the mock.
    Assert on the specific value we expect rather than total call count.
    """
    return any(call.args == (seconds,) for call in mock_sleep.call_args_list)


def test_search_spans__429_response__retries_with_reset_header(
    patched_sleep, capture_log
):
    rest_client = mock.Mock()
    rest_client.spans.search_spans.side_effect = [
        ApiError(status_code=429, headers={"RateLimit-Reset": "2"}),
        _stream(SPAN_RECORD),
    ]

    result = search_helpers.search_spans_with_filters(
        rest_client=rest_client,
        trace_id=None,
        project_name="proj",
        filters=None,
        max_results=10,
        truncate=False,
    )

    assert len(result) == 1
    assert result[0] == span_public.SpanPublic.model_validate(SPAN_RECORD)
    assert rest_client.spans.search_spans.call_count == 2
    assert _sleep_called_with(patched_sleep, 2)
    assert any(
        "search_spans" in record.message and record.levelno == logging.WARNING
        for record in capture_log.records
    )


def test_search_traces__429_response__retries_with_reset_header(
    patched_sleep, capture_log
):
    rest_client = mock.Mock()
    rest_client.traces.search_traces.side_effect = [
        ApiError(status_code=429, headers={"RateLimit-Reset": "3"}),
        _stream(TRACE_RECORD),
    ]

    result = search_helpers.search_traces_with_filters(
        rest_client=rest_client,
        project_name="proj",
        filters=None,
        max_results=10,
        truncate=False,
    )

    assert len(result) == 1
    assert result[0] == trace_public.TracePublic.model_validate(TRACE_RECORD)
    assert rest_client.traces.search_traces.call_count == 2
    assert _sleep_called_with(patched_sleep, 3)
    assert any(
        "search_traces" in record.message and record.levelno == logging.WARNING
        for record in capture_log.records
    )


def test_search_spans__non_429_error__propagates_without_retry():
    rest_client = mock.Mock()
    rest_client.spans.search_spans.side_effect = ApiError(
        status_code=400, body="bad request"
    )

    with pytest.raises(ApiError) as exc_info:
        search_helpers.search_spans_with_filters(
            rest_client=rest_client,
            trace_id=None,
            project_name="proj",
            filters=None,
            max_results=10,
            truncate=False,
        )

    assert exc_info.value.status_code == 400
    # call_count == 1 already proves no retry happened — no need to assert on
    # patched_sleep, which is polluted by background-thread sleeps.
    assert rest_client.spans.search_spans.call_count == 1


def test_search_traces__non_429_error__propagates_without_retry():
    rest_client = mock.Mock()
    rest_client.traces.search_traces.side_effect = ApiError(
        status_code=500, body="boom"
    )

    with pytest.raises(ApiError) as exc_info:
        search_helpers.search_traces_with_filters(
            rest_client=rest_client,
            project_name="proj",
            filters=None,
            max_results=10,
            truncate=False,
        )

    assert exc_info.value.status_code == 500
    assert rest_client.traces.search_traces.call_count == 1


def test_search_spans__429_without_reset_header__falls_back_to_one_second_sleep(
    patched_sleep,
):
    rest_client = mock.Mock()
    rest_client.spans.search_spans.side_effect = [
        ApiError(status_code=429, headers={}),
        _stream(SPAN_RECORD),
    ]

    result = search_helpers.search_spans_with_filters(
        rest_client=rest_client,
        trace_id=None,
        project_name="proj",
        filters=None,
        max_results=10,
        truncate=False,
    )

    assert len(result) == 1
    assert rest_client.spans.search_spans.call_count == 2
    assert _sleep_called_with(patched_sleep, 1)
