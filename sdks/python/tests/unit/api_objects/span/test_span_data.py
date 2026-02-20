import datetime

from opik.api_objects.span.span_data import SpanData
from ....testlib import assert_equal, ANY_BUT_NONE


def test_span_data__as_start_parameters__includes_parent_span_id():
    span_data = SpanData(
        trace_id="trace-1",
        id="span-1",
        parent_span_id="parent-1",
        start_time=datetime.datetime.now(),
        project_name="test",
        name="span-name",
    )

    expected_parameters = {
        "id": "span-1",
        "start_time": ANY_BUT_NONE,
        "project_name": "test",
        "trace_id": "trace-1",
        "parent_span_id": "parent-1",
        "name": "span-name",
    }

    assert_equal(expected_parameters, span_data.as_start_parameters)


def test_span_data__as_start_parameters__excludes_parent_span_id_when_none():
    span_data = SpanData(
        trace_id="trace-1",
        id="span-1",
        parent_span_id=None,
        start_time=datetime.datetime.now(),
        project_name="test",
    )

    params = span_data.as_start_parameters
    assert "parent_span_id" not in params
