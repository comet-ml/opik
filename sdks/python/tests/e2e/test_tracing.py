import pytest
import uuid

import opik
from opik import opik_context
from opik.api_objects import helpers
from . import verifiers
from .conftest import OPIK_E2E_TESTS_PROJECT_NAME

from ..testlib import ANY_STRING


@pytest.mark.parametrize(
    "project_name",
    [
        "e2e-tests-manual-project-name",
        None,
    ],
)
def test_tracked_function__happyflow(opik_client, project_name):
    # Setup
    ID_STORAGE = {}

    @opik.track(
        tags=["outer-tag1", "outer-tag2"],
        metadata={"outer-metadata-key": "outer-metadata-value"},
        project_name=project_name,
    )
    def f_outer(x):
        ID_STORAGE["f_outer-trace-id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["f_outer-span-id"] = opik_context.get_current_span_data().id

        f_inner("inner-input")
        return "outer-output"

    @opik.track(
        tags=["inner-tag1", "inner-tag2"],
        metadata={"inner-metadata-key": "inner-metadata-value"},
        project_name=project_name,
    )
    def f_inner(y):
        ID_STORAGE["f_inner-span-id"] = opik_context.get_current_span_data().id
        return "inner-output"

    # Call
    f_outer("outer-input")
    opik.flush_tracker()

    # Verify trace
    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
        metadata={"outer-metadata-key": "outer-metadata-value"},
        tags=["outer-tag1", "outer-tag2"],
        project_name=project_name or OPIK_E2E_TESTS_PROJECT_NAME,
    )

    # Verify top level span
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=ID_STORAGE["f_outer-span-id"],
        parent_span_id=None,
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
        metadata={"outer-metadata-key": "outer-metadata-value"},
        tags=["outer-tag1", "outer-tag2"],
        project_name=project_name or OPIK_E2E_TESTS_PROJECT_NAME,
    )

    # Verify nested span
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=ID_STORAGE["f_inner-span-id"],
        parent_span_id=ID_STORAGE["f_outer-span-id"],
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_inner",
        input={"y": "inner-input"},
        output={"output": "inner-output"},
        metadata={"inner-metadata-key": "inner-metadata-value"},
        tags=["inner-tag1", "inner-tag2"],
        project_name=project_name or OPIK_E2E_TESTS_PROJECT_NAME,
    )


def test_tracked_function__error_inside_inner_function__caugth_in_top_level_span__inner_span_has_error_info(
    opik_client,
):
    # Setup
    ID_STORAGE = {}

    @opik.track
    def f_inner(y):
        ID_STORAGE["f_inner-span-id"] = opik_context.get_current_span_data().id
        raise ValueError("inner span error message")

    @opik.track
    def f_outer(x):
        ID_STORAGE["f_outer-trace-id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["f_outer-span-id"] = opik_context.get_current_span_data().id

        try:
            f_inner("inner-input")
        except Exception:
            pass

        return "outer-output"

    # Call
    f_outer("outer-input")
    opik.flush_tracker()

    # Verify trace
    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
        error_info=None,
    )

    # Verify top level span
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=ID_STORAGE["f_outer-span-id"],
        parent_span_id=None,
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
    )

    # Verify nested span
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=ID_STORAGE["f_inner-span-id"],
        parent_span_id=ID_STORAGE["f_outer-span-id"],
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_inner",
        input={"y": "inner-input"},
        output=None,
        error_info={
            "exception_type": "ValueError",
            "message": "inner span error message",
            "traceback": ANY_STRING(),
        },
    )


def test_tracked_function__error_inside_inner_function__error_not_caugth__trace_and_its_spans_have_error_info(
    opik_client,
):
    # Setup
    ID_STORAGE = {}

    @opik.track
    def f_inner(y):
        ID_STORAGE["f_inner-span-id"] = opik_context.get_current_span_data().id
        raise ValueError("inner span error message")

    @opik.track
    def f_outer(x):
        ID_STORAGE["f_outer-trace-id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["f_outer-span-id"] = opik_context.get_current_span_data().id

        f_inner("inner-input")

    # Call
    with pytest.raises(ValueError):
        f_outer("outer-input")

    opik.flush_tracker()

    # Verify trace
    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_outer",
        input={"x": "outer-input"},
        output=None,
        error_info={
            "exception_type": "ValueError",
            "message": "inner span error message",
            "traceback": ANY_STRING(),
        },
    )

    # Verify top level span
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=ID_STORAGE["f_outer-span-id"],
        parent_span_id=None,
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_outer",
        input={"x": "outer-input"},
        output=None,
        error_info={
            "exception_type": "ValueError",
            "message": "inner span error message",
            "traceback": ANY_STRING(),
        },
    )

    # Verify nested span
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=ID_STORAGE["f_inner-span-id"],
        parent_span_id=ID_STORAGE["f_outer-span-id"],
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_inner",
        input={"y": "inner-input"},
        output=None,
        error_info={
            "exception_type": "ValueError",
            "message": "inner span error message",
            "traceback": ANY_STRING(),
        },
    )


def test_tracked_function__two_traces_and_two_spans__happyflow(opik_client):
    # Setup
    project_name = "e2e-tests-batching-messages"
    ID_STORAGE = {}

    @opik.track(project_name=project_name)
    def f1(x):
        ID_STORAGE["f1-trace-id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["f1-span-id"] = opik_context.get_current_span_data().id
        return "f1-output"

    @opik.track(project_name=project_name)
    def f2(y):
        ID_STORAGE["f2-trace-id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["f2-span-id"] = opik_context.get_current_span_data().id
        return "f2-output"

    # Call
    f1("f1-input")
    f2("f2-input")
    opik.flush_tracker()

    # Verify traces
    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=ID_STORAGE["f1-trace-id"],
        name="f1",
        input={"x": "f1-input"},
        output={"output": "f1-output"},
        project_name=project_name,
    )
    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=ID_STORAGE["f2-trace-id"],
        name="f2",
        input={"y": "f2-input"},
        output={"output": "f2-output"},
        project_name=project_name,
    )

    # Verify spans
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=ID_STORAGE["f1-span-id"],
        parent_span_id=None,
        trace_id=ID_STORAGE["f1-trace-id"],
        name="f1",
        input={"x": "f1-input"},
        output={"output": "f1-output"},
        project_name=project_name,
    )
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=ID_STORAGE["f2-span-id"],
        parent_span_id=None,
        trace_id=ID_STORAGE["f2-trace-id"],
        name="f2",
        input={"y": "f2-input"},
        output={"output": "f2-output"},
        project_name=project_name,
    )


def test_tracked_function__try_different_project_names(opik_client):
    """
    In this test we will try to use different project names for outer and inner spans.
    For both spans and for trace only outer span project name will be used.
    """
    # Setup
    project_name = "e2e-tests-manual-project-name--decorator"
    project_name2 = "e2e-tests-manual-project--this-will-be-ignored"

    ID_STORAGE = {}

    @opik.track(
        tags=["outer-tag1", "outer-tag2"],
        metadata={"outer-metadata-key": "outer-metadata-value"},
        project_name=project_name,
    )
    def f_outer(x):
        ID_STORAGE["f_outer-trace-id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["f_outer-span-id"] = opik_context.get_current_span_data().id

        f_inner("inner-input")
        return "outer-output"

    @opik.track(
        tags=["inner-tag1", "inner-tag2"],
        metadata={"inner-metadata-key": "inner-metadata-value"},
        project_name=project_name2,
    )
    def f_inner(y):
        ID_STORAGE["f_inner-span-id"] = opik_context.get_current_span_data().id
        return "inner-output"

    # Call
    f_outer("outer-input")
    opik.flush_tracker()

    # Verify trace
    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
        metadata={"outer-metadata-key": "outer-metadata-value"},
        tags=["outer-tag1", "outer-tag2"],
        project_name=project_name,
    )

    # Verify top level span
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=ID_STORAGE["f_outer-span-id"],
        parent_span_id=None,
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
        metadata={"outer-metadata-key": "outer-metadata-value"},
        tags=["outer-tag1", "outer-tag2"],
        project_name=project_name,
    )

    # Verify nested span
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=ID_STORAGE["f_inner-span-id"],
        parent_span_id=ID_STORAGE["f_outer-span-id"],
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_inner",
        input={"y": "inner-input"},
        output={"output": "inner-output"},
        metadata={"inner-metadata-key": "inner-metadata-value"},
        tags=["inner-tag1", "inner-tag2"],
        project_name=project_name,
    )


@pytest.mark.parametrize(
    "project_name",
    [
        "e2e-tests-manual-project-name",
        None,
    ],
)
def test_manually_created_trace_and_span__happyflow(
    opik_client: opik.Opik, project_name
):
    model_name = "some-llm"
    provider_name = "some-llm-provider"

    # Call
    trace = opik_client.trace(
        name="trace-name",
        input={"input": "trace-input"},
        output={"output": "trace-output"},
        tags=["trace-tag"],
        metadata={"trace-metadata-key": "trace-metadata-value"},
        project_name=project_name,
    )
    span = trace.span(
        name="span-name",
        input={"input": "span-input"},
        output={"output": "span-output"},
        tags=["span-tag"],
        metadata={"span-metadata-key": "span-metadata-value"},
        model=model_name,
        provider=provider_name,
    )

    opik_client.flush()

    # Verify trace
    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=trace.id,
        name="trace-name",
        input={"input": "trace-input"},
        output={"output": "trace-output"},
        tags=["trace-tag"],
        metadata={"trace-metadata-key": "trace-metadata-value"},
        project_name=project_name or OPIK_E2E_TESTS_PROJECT_NAME,
    )

    # Verify span
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=span.id,
        parent_span_id=None,
        trace_id=span.trace_id,
        name="span-name",
        input={"input": "span-input"},
        output={"output": "span-output"},
        tags=["span-tag"],
        metadata={"span-metadata-key": "span-metadata-value"},
        project_name=project_name or OPIK_E2E_TESTS_PROJECT_NAME,
        model=model_name,
        provider=provider_name,
    )


def test_search_traces__happyflow(opik_client):
    # In order to define a unique search query, we will create a unique identifier that will be part of the input of the trace
    unique_identifier = str(uuid.uuid4())[-6:]

    filter_string = f'input contains "{unique_identifier}"'

    # Send a trace that has this input
    trace = opik_client.trace(
        name="trace-name",
        input={"input": f"Some random input - {unique_identifier}"},
        output={"output": "trace-output"},
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )

    # Send traces that don't match
    non_matching_trace_ids = []
    for input_value in range(2):
        trace = opik_client.trace(
            name="trace-name",
            input={"input": "some-random-input"},
            output={"output": "trace-output"},
            project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        )
        non_matching_trace_ids.append(trace.id)
    opik_client.flush()

    # Search for the traces - Note that we use a large max_results to ensure that we get all traces, if the project has more than 100000 matching traces it is possible
    traces = opik_client.search_traces(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME, filter_string=filter_string
    )

    # Verify that the matching trace is returned
    assert len(traces) == 1, "Expected to find 1 matching trace"

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=traces[0].id,
        name="trace-name",
        input={"input": f"Some random input - {unique_identifier}"},
        output={"output": "trace-output"},
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )


def test_search_spans__happyflow(opik_client):
    # In order to define a unique search query, we will create a unique identifier that will be part of the input of the trace
    trace_id = helpers.generate_id()
    unique_identifier = str(uuid.uuid4())[-6:]

    filter_string = f'input contains "{unique_identifier}"'

    # Send a trace that matches the input filter
    trace = opik_client.trace(
        id=trace_id,
        name="trace-name",
        input={"input": "Some random input"},
        output={"output": "trace-output"},
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )
    matching_span = trace.span(
        name="span-name",
        input={"input": f"Some random input - {unique_identifier}"},
        output={"output": "span-output"},
    )
    trace.span(
        name="span-name",
        input={"input": "Some random input 1"},
        output={"output": "span-output"},
    )
    trace.span(
        name="span-name",
        input={"input": "Some random input 2"},
        output={"output": "span-output"},
    )

    opik_client.flush()

    # Search for the traces - Note that we use a large max_results to ensure that we get all traces,
    # if the project has more than 100000 matching traces it is possible
    spans = opik_client.search_spans(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        trace_id=trace_id,
        filter_string=filter_string,
    )

    # Verify that the matching trace is returned
    assert len(spans) == 1, "Expected to find 1 matching span"
    assert spans[0].id == matching_span.id, "Expected to find the matching span"


def test_copy_traces__happyflow(opik_client):
    # Log traces
    unique_identifier = str(uuid.uuid4())[-6:]

    project_name = f"e2e-tests-copy-traces-project - {unique_identifier}"
    for i in range(3):
        trace = opik_client.trace(
            name="trace",
            project_name=project_name,
            input={"input": f"test input - {i}"},
        )

        span = trace.span(
            name="span",
            input={"input": f"test input - {i}"},
        )

        span.span(
            name="nested span",
            input={"input": f"test input - {i}"},
        )
    opik_client.flush()

    new_project_name = project_name + "_v2"
    opik_client.copy_traces(
        project_name=project_name,
        destination_project_name=new_project_name,
    )

    opik_client.flush()

    traces = opik_client.search_traces(project_name=new_project_name)

    assert len(traces) == 3

    spans = opik_client.search_spans(project_name=new_project_name)

    assert len(spans) == 6


def test_tracked_function__update_current_span_used_to_update_cost__happyflow(
    opik_client,
):
    # Setup
    ID_STORAGE = {}

    @opik.track
    def f():
        opik_context.update_current_span(total_cost=0.42)
        ID_STORAGE["f_span-id"] = opik_context.get_current_span_data().id
        ID_STORAGE["f_trace-id"] = opik_context.get_current_trace_data().id

    # Call
    f()
    opik.flush_tracker()

    # Verify top level span
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=ID_STORAGE["f_span-id"],
        parent_span_id=None,
        trace_id=ID_STORAGE["f_trace-id"],
        name="f",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        total_cost=0.42,
    )
