import os
import time
import uuid

import pytest

import opik
from opik import opik_context, id_helpers, Attachment, exceptions
from opik.api_objects import helpers
from opik.types import FeedbackScoreDict, ErrorInfoDict
from . import verifiers
from .conftest import OPIK_E2E_TESTS_PROJECT_NAME, ATTACHMENT_FILE_SIZE
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


def test_tracked_function__error_inside_inner_function__caught_in_top_level_span__inner_span_has_error_info(
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
        error_info=ErrorInfoDict(
            exception_type="ValueError",
            message="inner span error message",
            traceback=ANY_STRING,
        ),
    )


def test_tracked_function__error_inside_inner_function__error_not_caught__trace_and_its_spans_have_error_info(
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
        error_info=ErrorInfoDict(
            exception_type="ValueError",
            message="inner span error message",
            traceback=ANY_STRING,
        ),
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
        error_info=ErrorInfoDict(
            exception_type="ValueError",
            message="inner span error message",
            traceback=ANY_STRING,
        ),
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
        error_info=ErrorInfoDict(
            exception_type="ValueError",
            message="inner span error message",
            traceback=ANY_STRING,
        ),
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
        metadata={
            "providers": [
                provider_name
            ],  # BE injects "providers" array as first field in metadata of trace
            "trace-metadata-key": "trace-metadata-value",
        },
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
        metadata={
            "provider": provider_name,  # BE injects "provider" string as first field in metadata of span
            "span-metadata-key": "span-metadata-value",
        },
        project_name=project_name or OPIK_E2E_TESTS_PROJECT_NAME,
        model=model_name,
        provider=provider_name,
    )


def test_search_traces__happyflow(opik_client):
    # To define a unique search query, we will create a unique identifier that will be part of the trace input
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
    for input_value in range(2):
        opik_client.trace(
            name="trace-name",
            input={"input": "some-random-input"},
            output={"output": "trace-output"},
            project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        )

    opik_client.flush()

    # Search for the traces - Note that we use a large max_results to ensure that we get all traces, if the project has more than 100000 matching traces it is possible
    traces = opik_client.search_traces(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME, filter_string=filter_string
    )

    # Verify that the matching trace is returned
    assert len(traces) == 1, "Expected to find 1 matching trace"

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=trace.id,
        name="trace-name",
        input={"input": f"Some random input - {unique_identifier}"},
        output={"output": "trace-output"},
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )


def test_search_traces__wait_for_at_least__happyflow(opik_client):
    # check that synchronized searching for traces is working
    unique_identifier = str(uuid.uuid4())[-6:]

    # Send traces that have this input
    trace_ids = []
    matching_count = 1000
    for i in range(matching_count):
        trace = opik_client.trace(
            name=f"trace-name-{i}",
            input={"input": f"Some random input - {unique_identifier}"},
            output={"output": "trace-output"},
            project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        )
        trace_ids.append(trace.id)

    # send not matching traces
    opik_client.trace(
        name="trace-name",
        input={"input": "some-random-input-1"},
    )
    opik_client.trace(
        name="trace-name",
        input={"input": "some-random-input-2"},
    )

    opik_client.flush()

    # Search for the traces with synchronization
    filter_string = f'input contains "{unique_identifier}"'
    traces = opik_client.search_traces(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        filter_string=filter_string,
        wait_for_at_least=matching_count,
        wait_for_timeout=10,
    )

    # Verify that the matching trace is returned
    assert (
        len(traces) == matching_count
    ), f"Expected to find {matching_count} matching traces"
    for trace in traces:
        assert (
            trace.id in trace_ids
        ), f"Expected to find the matching trace id {trace.id}"


def test_search_traces__wait_for_at_least__timeout__exception_raised(opik_client):
    # check that synchronized searching for traces is working
    unique_identifier = str(uuid.uuid4())[-6:]

    # Send traces that have this input
    opik_client.trace(
        name="trace-name-3",
        input={"input": f"Some random input - {unique_identifier}"},
        output={"output": "trace-output"},
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )

    # send not matching traces
    opik_client.trace(
        name="trace-name",
        input={"input": "some-random-input-1"},
    )
    opik_client.trace(
        name="trace-name",
        input={"input": "some-random-input-2"},
    )

    opik_client.flush()

    # Search for the traces with synchronization
    unmatchable_count = 1000
    filter_string = f'input contains "{unique_identifier}"'
    with pytest.raises(exceptions.SearchTimeoutError):
        opik_client.search_traces(
            project_name=OPIK_E2E_TESTS_PROJECT_NAME,
            filter_string=filter_string,
            wait_for_at_least=unmatchable_count,
            wait_for_timeout=1,
        )


def test_search_spans__happyflow(opik_client: opik.Opik):
    # To define a unique search query, we will create a unique identifier that will be part of the trace input
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

    # Search for the spans
    spans = opik_client.search_spans(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        trace_id=trace_id,
        filter_string=filter_string,
    )

    # Verify that the matching trace is returned
    assert len(spans) == 1, "Expected to find 1 matching span"
    assert spans[0].id == matching_span.id, "Expected to find the matching span"


def test_search_spans__wait_for_at_least__happy_flow(opik_client: opik.Opik):
    # check that synchronized searching for spans is working
    trace_id = helpers.generate_id()
    unique_identifier = str(uuid.uuid4())[-6:]

    # Send a trace that matches the input filter
    trace = opik_client.trace(
        id=trace_id,
        name="trace-name",
        input={"input": "Some random input"},
        output={"output": "trace-output"},
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )
    matching_count = 1000
    matching_span_ids = []
    for i in range(matching_count):
        matching_span = trace.span(
            name=f"span-name-{i}",
            input={"input": f"Some random input - {unique_identifier}"},
            output={"output": "span-output"},
        )
        matching_span_ids.append(matching_span.id)

    # adding two not matching spans
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

    filter_string = f'input contains "{unique_identifier}"'

    # Search for the spans with synchronization
    spans = opik_client.search_spans(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        trace_id=trace_id,
        filter_string=filter_string,
        wait_for_at_least=matching_count,
        wait_for_timeout=10,
    )

    # Verify that the matching trace is returned
    assert (
        len(spans) == matching_count
    ), f"Expected to find {matching_count} matching spans"
    for span in spans:
        assert (
            span.id in matching_span_ids
        ), f"Expected to find the matching span id {span.id}"


def test_search_spans__wait_for_at_least__timeout__exception_raised(
    opik_client: opik.Opik,
):
    trace_id = helpers.generate_id()
    unique_identifier = str(uuid.uuid4())[-6:]

    # Send a trace that matches the input filter
    trace = opik_client.trace(
        id=trace_id,
        name="trace-name",
        input={"input": "Some random input"},
        output={"output": "trace-output"},
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )
    trace.span(
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

    # Search for the spans
    unmatchable_count = 1000
    filter_string = f'input contains "{unique_identifier}"'
    with pytest.raises(exceptions.SearchTimeoutError):
        opik_client.search_spans(
            project_name=OPIK_E2E_TESTS_PROJECT_NAME,
            trace_id=trace_id,
            filter_string=filter_string,
            wait_for_at_least=unmatchable_count,
            wait_for_timeout=1,
        )


def test_copy_traces__happyflow(opik_client):
    # Log traces
    unique_identifier = str(uuid.uuid4())[-6:]
    ID_STORAGE = {}

    project_name = f"e2e-tests-copy-traces-project - {unique_identifier}"
    for i in range(2):
        trace = opik_client.trace(
            name="trace",
            project_name=project_name,
            input={"input": f"test input - {i}"},
            output={"output": f"test output - {i}"},
            feedback_scores=[
                {
                    "name": "score_trace",
                    "value": i,
                    "category_name": "category_",
                    "reason": "reason_",
                }
            ],
            metadata={"value": i},
            tags=["a", "b"],
        )
        ID_STORAGE[f"trace-{i}-id"] = trace.id

        trace.span(
            name="span - 0",
            input={"input": f"test input - {i} - 0"},
        )

        # Sleep so span timestamps are ordered due to uuid7
        time.sleep(0.001)

        trace.span(
            name="span - 1",
            input={"input": f"test input - {i} - 1"},
        )

    opik_client.flush()

    new_project_name = project_name + "_v2"
    opik_client.copy_traces(
        project_name=project_name,
        destination_project_name=new_project_name,
    )

    opik_client.flush()

    traces = opik_client.search_traces(project_name=new_project_name)
    for i, trace in enumerate(reversed(traces)):
        verifiers.verify_trace(
            opik_client=opik_client,
            trace_id=trace.id,
            name="trace",
            input={"input": f"test input - {i}"},
            output={"output": f"test output - {i}"},
            feedback_scores=[
                FeedbackScoreDict(
                    id=trace.id,
                    name="score_trace",
                    value=i,
                    category_name="category_",
                    reason="reason_",
                )
            ],
            metadata={"value": i},
            tags=["a", "b"],
            project_name=new_project_name,
        )

        trace_spans = opik_client.search_spans(
            project_name=new_project_name, trace_id=trace.id
        )
        for j, span in enumerate(reversed(trace_spans)):
            verifiers.verify_span(
                opik_client=opik_client,
                span_id=span.id,
                trace_id=trace.id,
                name=f"span - {j}",
                input={"input": f"test input - {i} - {j}"},
                parent_span_id=span.parent_span_id,
                project_name=new_project_name,
            )


def test_tracked_function__update_current_span_and_trace_called__happyflow(
    opik_client,
):
    # Setup
    ID_STORAGE = {}
    THREAD_ID = id_helpers.generate_id()

    @opik.track
    def f():
        opik_context.update_current_span(
            name="span-name",
            input={"span-input": "span-input-value"},
            output={"span-output": "span-output-value"},
            metadata={"span-metadata-key": "span-metadata-value"},
            total_cost=0.42,
        )
        opik_context.update_current_trace(
            name="trace-name",
            input={"trace-input": "trace-input-value"},
            output={"trace-output": "trace-output-value"},
            metadata={"trace-metadata-key": "trace-metadata-value"},
            thread_id=THREAD_ID,
        )
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
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        name="span-name",
        input={"span-input": "span-input-value"},
        output={"span-output": "span-output-value"},
        metadata={"span-metadata-key": "span-metadata-value"},
        total_cost=0.42,
    )

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=ID_STORAGE["f_trace-id"],
        name="trace-name",
        input={"trace-input": "trace-input-value"},
        output={"trace-output": "trace-output-value"},
        metadata={"trace-metadata-key": "trace-metadata-value"},
        thread_id=THREAD_ID,
    )


def test_opik_trace__attachments(opik_client, attachment_data_file):
    trace_id = helpers.generate_id()
    file_name = os.path.basename(attachment_data_file.name)
    names = [file_name + "_first", file_name + "_second"]
    attachments = {
        names[0]: Attachment(
            data=attachment_data_file.name,
            file_name=names[0],
            content_type="application/octet-stream",
        ),
        names[1]: Attachment(
            data=attachment_data_file.name,
            file_name=names[1],
            content_type="application/octet-stream",
        ),
    }
    data_sizes = {
        names[0]: ATTACHMENT_FILE_SIZE,
        names[1]: ATTACHMENT_FILE_SIZE,
    }

    # Send a trace that matches the input filter
    opik_client.trace(
        id=trace_id,
        name="trace-name",
        input={"input": "Some random input"},
        output={"output": "trace-output"},
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        attachments=attachments.values(),
    )

    opik_client.flush()

    # check that the attachment was uploaded
    verifiers.verify_attachments(
        opik_client=opik_client,
        entity_type="trace",
        entity_id=trace_id,
        attachments=attachments,
        data_sizes=data_sizes,
    )


def test_tracked_function__update_current_trace__with_attachments(
    opik_client, attachment_data_file
):
    # Setup
    ID_STORAGE = {}
    THREAD_ID = id_helpers.generate_id()

    file_name = os.path.basename(attachment_data_file.name)
    attachments = {
        file_name: Attachment(
            data=attachment_data_file.name,
            file_name=file_name,
            content_type="application/octet-stream",
        )
    }
    data_sizes = {
        file_name: ATTACHMENT_FILE_SIZE,
    }

    @opik.track
    def f():
        opik_context.update_current_trace(
            name="trace-name",
            input={"trace-input": "trace-input-value"},
            output={"trace-output": "trace-output-value"},
            metadata={"trace-metadata-key": "trace-metadata-value"},
            thread_id=THREAD_ID,
            attachments=attachments.values(),
        )
        ID_STORAGE["f_trace-id"] = opik_context.get_current_trace_data().id

    # Call
    f()
    opik.flush_tracker()

    # check that the attachment was uploaded
    verifiers.verify_attachments(
        opik_client=opik_client,
        entity_type="trace",
        entity_id=ID_STORAGE["f_trace-id"],
        attachments=attachments,
        data_sizes=data_sizes,
    )


def test_opik_client_span__attachments(opik_client, attachment_data_file):
    trace_id = helpers.generate_id()
    file_name = os.path.basename(attachment_data_file.name)
    names = [file_name + "_first", file_name + "_second"]
    attachments = {
        names[0]: Attachment(
            data=attachment_data_file.name,
            file_name=names[0],
            content_type="application/octet-stream",
        ),
        names[1]: Attachment(
            data=attachment_data_file.name,
            file_name=names[1],
            content_type="application/octet-stream",
        ),
    }
    data_sizes = {
        names[0]: ATTACHMENT_FILE_SIZE,
        names[1]: ATTACHMENT_FILE_SIZE,
    }

    # Send a trace that matches the input filter
    opik_client.trace(
        id=trace_id,
        name="trace-name",
        input={"input": "Some random input"},
        output={"output": "trace-output"},
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )
    span = opik_client.span(
        trace_id=trace_id,
        name="span-name",
        input={"input": "Some random input 2"},
        output={"output": "span-output"},
        attachments=attachments.values(),
    )

    opik_client.flush()

    # check that the attachment was uploaded
    verifiers.verify_attachments(
        opik_client=opik_client,
        entity_type="span",
        entity_id=span.id,
        attachments=attachments,
        data_sizes=data_sizes,
    )


def test_span_span__attachments(opik_client, attachment_data_file):
    trace_id = helpers.generate_id()
    file_name = os.path.basename(attachment_data_file.name)
    names = [file_name + "_first", file_name + "_second"]
    attachments = {
        names[0]: Attachment(
            data=attachment_data_file.name,
            file_name=names[0],
            content_type="application/octet-stream",
        ),
        names[1]: Attachment(
            data=attachment_data_file.name,
            file_name=names[1],
            content_type="application/octet-stream",
        ),
    }
    data_sizes = {
        names[0]: ATTACHMENT_FILE_SIZE,
        names[1]: ATTACHMENT_FILE_SIZE,
    }

    # Send a trace that matches the input filter
    opik_client.trace(
        id=trace_id,
        name="trace-name",
        input={"input": "Some random input"},
        output={"output": "trace-output"},
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )
    span = opik_client.span(
        trace_id=trace_id,
        name="span-name",
        input={"input": "Some random input 2"},
        output={"output": "span-output"},
    )
    last_span = span.span(
        name="span-name",
        input={"input": "Some random input 2"},
        output={"output": "span-output"},
        attachments=attachments.values(),
    )

    opik_client.flush()

    # check that the attachment was uploaded
    verifiers.verify_attachments(
        opik_client=opik_client,
        entity_type="span",
        entity_id=last_span.id,
        attachments=attachments,
        data_sizes=data_sizes,
    )


def test_trace_span__attachments(opik_client, attachment_data_file):
    trace_id = helpers.generate_id()
    file_name = os.path.basename(attachment_data_file.name)
    names = [file_name + "_first", file_name + "_second"]
    attachments = {
        names[0]: Attachment(
            data=attachment_data_file.name,
            file_name=names[0],
            content_type="application/octet-stream",
        ),
        names[1]: Attachment(
            data=attachment_data_file.name,
            file_name=names[1],
            content_type="application/octet-stream",
        ),
    }
    data_sizes = {
        names[0]: ATTACHMENT_FILE_SIZE,
        names[1]: ATTACHMENT_FILE_SIZE,
    }

    # Send a trace that matches the input filter
    trace = opik_client.trace(
        id=trace_id,
        name="trace-name",
        input={"input": "Some random input"},
        output={"output": "trace-output"},
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )
    span = trace.span(
        name="span-name",
        input={"input": "Some random input 2"},
        output={"output": "span-output"},
        attachments=attachments.values(),
    )

    opik_client.flush()

    # check that the attachment was uploaded
    verifiers.verify_attachments(
        opik_client=opik_client,
        entity_type="span",
        entity_id=span.id,
        attachments=attachments,
        data_sizes=data_sizes,
    )


def test_tracked_function__update_current_span__with_attachments(
    opik_client, attachment_data_file
):
    # Setup
    ID_STORAGE = {}
    THREAD_ID = id_helpers.generate_id()

    file_name = os.path.basename(attachment_data_file.name)
    attachments = {
        file_name: Attachment(
            data=attachment_data_file.name,
            file_name=file_name,
            content_type="application/octet-stream",
        )
    }
    data_sizes = {
        file_name: ATTACHMENT_FILE_SIZE,
    }

    @opik.track
    def f():
        opik_context.update_current_span(
            name="span-name",
            input={"span-input": "span-input-value"},
            output={"span-output": "span-output-value"},
            metadata={"span-metadata-key": "span-metadata-value"},
            total_cost=0.42,
            attachments=attachments.values(),
        )
        opik_context.update_current_trace(
            name="trace-name",
            input={"trace-input": "trace-input-value"},
            output={"trace-output": "trace-output-value"},
            metadata={"trace-metadata-key": "trace-metadata-value"},
            thread_id=THREAD_ID,
        )
        ID_STORAGE["f_span-id"] = opik_context.get_current_span_data().id

    # Call
    f()
    opik.flush_tracker()

    # check that the attachment was uploaded
    verifiers.verify_attachments(
        opik_client=opik_client,
        entity_type="span",
        entity_id=ID_STORAGE["f_span-id"],
        attachments=attachments,
        data_sizes=data_sizes,
    )


def test_opik_client__update_span_with_attachments__original_fields_preserved_but_some_are_patched(
    opik_client: opik.Opik, attachment_data_file
):
    root_span_client = opik_client.span(
        name="root-span-name",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )
    child_span_client = root_span_client.span(
        name="child-span-name",
        input={"input": "original-span-input"},
        output={"output": "original-span-output"},
    )
    opik_client.flush()

    file_name = os.path.basename(attachment_data_file.name)
    attachments = {
        file_name: Attachment(
            data=attachment_data_file.name,
            file_name=file_name,
            content_type="application/octet-stream",
        )
    }
    data_sizes = {
        file_name: ATTACHMENT_FILE_SIZE,
    }

    opik_client.update_span(
        id=child_span_client.id,
        trace_id=child_span_client.trace_id,
        parent_span_id=child_span_client.parent_span_id,
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        input={"input": "new-span-input"},
        attachments=attachments.values(),
    )
    opik_client.flush()

    verifiers.verify_span(
        opik_client=opik_client,
        span_id=child_span_client.id,
        parent_span_id=root_span_client.id,
        trace_id=child_span_client.trace_id,
        input={"input": "new-span-input"},
        output={"output": "original-span-output"},
        name="child-span-name",
    )
    verifiers.verify_attachments(
        opik_client=opik_client,
        entity_type="span",
        entity_id=child_span_client.id,
        attachments=attachments,
        data_sizes=data_sizes,
        timeout=30,
    )


@pytest.mark.parametrize(
    "new_input,new_output,new_tags,new_metadata,new_thread_id",
    [
        ({"input": "new-trace-input-value"}, None, None, None, None),
        (None, {"output": "new-trace-output-value"}, None, None, None),
        (None, None, ["new-trace-tag"], None, None),
        (
            None,
            None,
            None,
            {"new-trace-metadata-key": "new-trace-metadata-value"},
            None,
        ),
        (None, None, None, None, id_helpers.generate_id()),
    ],
)
def test_opik_client__update_trace__happy_flow(
    new_input, new_output, new_tags, new_metadata, new_thread_id, opik_client: opik.Opik
):
    # test that the trace update works by updating only one field at a time
    project_name = "update_trace_happy_flow"
    trace_name = "trace_name"
    input = {"input": "trace-input-value"}
    output = {"output": "trace-output-value"}
    tags = ["trace-tag"]
    metadata = {"trace-metadata-key": "trace-metadata-value"}
    thread_id = id_helpers.generate_id()
    trace = opik_client.trace(
        name=trace_name,
        input=input,
        output=output,
        tags=tags,
        metadata=metadata,
        project_name=project_name,
        thread_id=thread_id,
    )

    opik_client.flush()

    # verify that the trace was saved
    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=trace.id,
        name=trace_name,
        project_name=project_name,
        input=input,
        output=output,
        metadata=metadata,
        tags=tags,
        thread_id=thread_id,
    )

    #
    # Do partial update
    #
    opik_client.update_trace(
        trace_id=trace.id,
        project_name=project_name,
        input=new_input,
        output=new_output,
        tags=new_tags,
        metadata=new_metadata,
        thread_id=new_thread_id,
    )

    # flush to make sure the update was logged to server
    opik_client.flush()

    input = new_input or input
    output = new_output or output
    tags = new_tags or tags
    metadata = new_metadata or metadata
    thread_id = new_thread_id or thread_id

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=trace.id,
        name=trace_name,
        project_name=project_name,
        input=input,
        output=output,
        tags=tags,
        metadata=metadata,
        thread_id=thread_id,
    )


def test_search_traces__filter_by_feedback_score__is_empty_and_equals(
    opik_client: opik.Opik,
):
    # Create a unique metric name to avoid conflicts with other tests
    unique_metric = f"test_metric_{str(uuid.uuid4()).replace('-', '_')[-8:]}"

    # Create trace with the feedback score
    trace_with_score = opik_client.trace(
        name="trace-with-score",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )
    trace_with_score.log_feedback_score(
        unique_metric, value=0.75, category_name="test-category", reason="test-reason"
    )

    # Create trace without the feedback score
    trace_without_score = opik_client.trace(
        name="trace-without-score",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )

    opik_client.flush()

    # Test filtering with is_empty - should find trace without the score
    traces_empty = opik_client.search_traces(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        filter_string=f"feedback_scores.{unique_metric} is_empty",
    )
    trace_ids_empty = {trace.id for trace in traces_empty}
    assert (
        trace_without_score.id in trace_ids_empty
    ), "Trace without score should be found with is_empty filter"
    assert (
        trace_with_score.id not in trace_ids_empty
    ), "Trace with score should not be found with is_empty filter"

    # Test filtering with is_not_empty - should find trace with the score
    traces_not_empty = opik_client.search_traces(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        filter_string=f"feedback_scores.{unique_metric} is_not_empty",
    )
    trace_ids_not_empty = {trace.id for trace in traces_not_empty}
    assert (
        trace_with_score.id in trace_ids_not_empty
    ), "Trace with score should be found with is_not_empty filter"
    assert (
        trace_without_score.id not in trace_ids_not_empty
    ), "Trace without score should not be found with is_not_empty filter"

    # Test filtering with = operator - should find trace with the specific score value
    traces_with_value = opik_client.search_traces(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        filter_string=f"feedback_scores.{unique_metric} = 0.75",
    )
    trace_ids_with_value = {trace.id for trace in traces_with_value}
    assert (
        trace_with_score.id in trace_ids_with_value
    ), "Trace with score value 0.75 should be found"
    assert (
        trace_without_score.id not in trace_ids_with_value
    ), "Trace without score should not be found"

    # Verify is_not_empty and = return the same trace
    assert (
        trace_ids_not_empty == trace_ids_with_value
    ), "is_not_empty and = filters should return the same traces for this test case"


def test_search_spans__filter_by_feedback_score__is_empty_and_equals(
    opik_client: opik.Opik,
):
    # Create a unique metric name to avoid conflicts with other tests
    unique_metric = f"test_metric_{str(uuid.uuid4()).replace('-', '_')[-8:]}"
    trace_id = helpers.generate_id()

    # Create a trace with two spans
    trace = opik_client.trace(
        id=trace_id,
        name="trace-name",
        input={"input": "Some random input"},
        output={"output": "trace-output"},
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )

    # Create span with the feedback score
    span_with_score = trace.span(
        name="span-with-score",
        input={"input": "span-input-1"},
        output={"output": "span-output-1"},
    )
    span_with_score.log_feedback_score(
        unique_metric, value=0.85, category_name="test-category", reason="test-reason"
    )

    # Create span without the feedback score
    span_without_score = trace.span(
        name="span-without-score",
        input={"input": "span-input-2"},
        output={"output": "span-output-2"},
    )

    opik_client.flush()

    # Test filtering with is_empty - should find span without the score
    spans_empty = opik_client.search_spans(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        trace_id=trace_id,
        filter_string=f"feedback_scores.{unique_metric} is_empty",
    )
    span_ids_empty = {span.id for span in spans_empty}
    assert (
        span_without_score.id in span_ids_empty
    ), "Span without score should be found with is_empty filter"
    assert (
        span_with_score.id not in span_ids_empty
    ), "Span with score should not be found with is_empty filter"

    # Test filtering with is_not_empty - should find span with the score
    spans_not_empty = opik_client.search_spans(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        trace_id=trace_id,
        filter_string=f"feedback_scores.{unique_metric} is_not_empty",
    )
    span_ids_not_empty = {span.id for span in spans_not_empty}
    assert (
        span_with_score.id in span_ids_not_empty
    ), "Span with score should be found with is_not_empty filter"
    assert (
        span_without_score.id not in span_ids_not_empty
    ), "Span without score should not be found with is_not_empty filter"

    # Test filtering with = operator - should find span with the specific score value
    spans_with_value = opik_client.search_spans(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        trace_id=trace_id,
        filter_string=f"feedback_scores.{unique_metric} = 0.85",
    )
    span_ids_with_value = {span.id for span in spans_with_value}
    assert (
        span_with_score.id in span_ids_with_value
    ), "Span with score value 0.85 should be found"
    assert (
        span_without_score.id not in span_ids_with_value
    ), "Span without score should not be found"

    # Verify is_not_empty and = return the same span
    assert (
        span_ids_not_empty == span_ids_with_value
    ), "is_not_empty and = filters should return the same spans for this test case"
