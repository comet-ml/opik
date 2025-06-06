import datetime

from opik.api_objects import trace
from ....testlib import assert_equal, ANY_BUT_NONE


def test_trace_data__as_start_parameters__expected_parameters_are_set():
    trace_data = trace.TraceData(
        name="name",
        project_name="project_name",
        metadata={"foo": "bar"},
        input={"input": "input"},
        tags=["one", "two"],
        created_by="evaluation",
    )

    expected_parameters = {
        "id": ANY_BUT_NONE,
        "start_time": ANY_BUT_NONE,
        "project_name": "project_name",
        "name": "name",
        "metadata": {"foo": "bar"},
        "input": {"input": "input"},
        "tags": ["one", "two"],
    }

    assert_equal(expected_parameters, trace_data.as_start_parameters)


def test_trace_data__as_parameters__expected_parameters_are_set():
    trace_data = trace.TraceData(
        name="name",
        end_time=datetime.datetime.now(),
        metadata={"foo": "bar"},
        input={"input": "input"},
        output={"output": "output"},
        tags=["one", "two"],
        feedback_scores=[{"name": "score_name", "value": 0.5}],
        project_name="project_name",
        error_info={},
        thread_id="thread_id",
        attachments=[],
        created_by="evaluation",
    )

    expected_parameters = {
        "id": ANY_BUT_NONE,
        "name": "name",
        "start_time": ANY_BUT_NONE,
        "end_time": ANY_BUT_NONE,
        "metadata": ANY_BUT_NONE,
        "input": ANY_BUT_NONE,
        "output": ANY_BUT_NONE,
        "tags": ANY_BUT_NONE,
        "feedback_scores": ANY_BUT_NONE,
        "project_name": "project_name",
        "error_info": ANY_BUT_NONE,
        "thread_id": ANY_BUT_NONE,
        "attachments": ANY_BUT_NONE,
    }

    assert_equal(expected_parameters, trace_data.as_parameters)
