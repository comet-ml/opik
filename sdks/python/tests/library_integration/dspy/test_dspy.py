from typing import Union

import dspy
import pytest

import opik
from opik import context_storage
from opik.api_objects import opik_client, span, trace
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.dspy.callback import OpikCallback
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)


def sort_spans_by_name(tree: Union[SpanModel, TraceModel]) -> None:
    """
    Sorts the spans within a trace/span tree by their names in ascending order.
    """
    tree.spans = sorted(tree.spans, key=lambda span: span.name)


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("dspy-integration-test", "dspy-integration-test"),
    ],
)
def test_dspy__happyflow(
    fake_backend,
    project_name,
    expected_project_name,
):
    lm = dspy.LM(
        cache=False,
        model="openai/gpt-4o-mini",
    )
    dspy.configure(lm=lm)

    opik_callback = OpikCallback(project_name=project_name)
    dspy.settings.configure(callbacks=[opik_callback])

    cot = dspy.ChainOfThought("question -> answer")
    cot(question="What is the meaning of life?")

    opik_callback.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_STRING(),
        name="ChainOfThought",
        input={"args": [], "kwargs": {"question": "What is the meaning of life?"}},
        output=None,
        metadata={"created_from": "dspy"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_STRING(),
                type="llm",
                name="Predict",
                provider=None,
                model=None,
                input=ANY_DICT,
                output=ANY_DICT,
                metadata={"created_from": "dspy"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[
                    SpanModel(
                        id=ANY_STRING(),
                        type="llm",
                        name=ANY_STRING(startswith="LM"),
                        provider="openai",
                        model="gpt-4o-mini",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        metadata={"created_from": "dspy"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=expected_project_name,
                        spans=[],
                    ),
                ],
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(fake_backend.span_trees) == 1

    sort_spans_by_name(EXPECTED_TRACE_TREE)
    sort_spans_by_name(fake_backend.trace_trees[0])

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_dspy__openai_llm_is_used__error_occurred_during_openai_call__error_info_is_logged(
    fake_backend,
):
    lm = dspy.LM(
        cache=False,
        model="openai/gpt-3.5-turbo",
        api_key="incorrect-api-key",
    )
    dspy.configure(lm=lm)

    project_name = "dspy-integration-test"
    opik_callback = OpikCallback(project_name=project_name)
    dspy.settings.configure(callbacks=[opik_callback])

    cot = dspy.ChainOfThought("question -> answer")

    with pytest.raises(Exception):
        cot(question="What is the meaning of life?")

    opik_callback.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_STRING(),
        name="ChainOfThought",
        input={"args": [], "kwargs": {"question": "What is the meaning of life?"}},
        output=None,
        metadata={"created_from": "dspy"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=project_name,
        spans=[
            SpanModel(
                id=ANY_STRING(),
                type="llm",
                name="Predict",
                provider=None,
                model=None,
                input=ANY_DICT,
                output=ANY_DICT,
                metadata={"created_from": "dspy"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                error_info={
                    "exception_type": ANY_STRING(),
                    "message": ANY_STRING(),
                    "traceback": ANY_STRING(),
                },
                spans=[
                    SpanModel(
                        id=ANY_STRING(),
                        type="llm",
                        name=ANY_STRING(startswith="LM: "),
                        provider="openai",
                        model="gpt-3.5-turbo",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        metadata={"created_from": "dspy"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        spans=[],
                        error_info={
                            "exception_type": ANY_STRING(),
                            "message": ANY_STRING(),
                            "traceback": ANY_STRING(),
                        },
                    ),
                    SpanModel(
                        id=ANY_STRING(),
                        type="llm",
                        name=ANY_STRING(startswith="LM: "),
                        provider="openai",
                        model="gpt-3.5-turbo",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        metadata={"created_from": "dspy"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        spans=[],
                        error_info={
                            "exception_type": ANY_STRING(),
                            "message": ANY_STRING(),
                            "traceback": ANY_STRING(),
                        },
                    ),
                ],
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(fake_backend.span_trees) == 1

    sort_spans_by_name(EXPECTED_TRACE_TREE)
    sort_spans_by_name(fake_backend.trace_trees[0])

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_dspy_callback__used_inside_another_track_function__data_attached_to_existing_trace_tree(
    fake_backend,
):
    project_name = "dspy-integration-test"

    @opik.track(project_name=project_name, capture_output=True)
    def f(x):
        lm = dspy.LM(
            cache=False,
            model="openai/gpt-3.5-turbo",
        )
        dspy.configure(lm=lm)

        opik_callback = OpikCallback(project_name=project_name)
        dspy.settings.configure(callbacks=[opik_callback])

        cot = dspy.ChainOfThought("question -> answer")
        cot(question="What is the meaning of life?")

        opik_callback.flush()

        return "the-output"

    f("the-input")
    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={"x": "the-input"},
        output={"output": "the-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                type="general",
                input={"x": "the-input"},
                output={"output": "the-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                spans=[
                    SpanModel(
                        id=ANY_STRING(),
                        name="ChainOfThought",
                        input={
                            "args": [],
                            "kwargs": {"question": "What is the meaning of life?"},
                        },
                        output=ANY_DICT,
                        metadata={"created_from": "dspy"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        spans=[
                            SpanModel(
                                id=ANY_STRING(),
                                type="llm",
                                name="Predict",
                                provider=None,
                                model=None,
                                input=ANY_DICT,
                                output=ANY_DICT,
                                metadata={"created_from": "dspy"},
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                project_name=project_name,
                                spans=[
                                    SpanModel(
                                        id=ANY_STRING(),
                                        type="llm",
                                        name=ANY_STRING(startswith="LM: openai"),
                                        provider="openai",
                                        model="gpt-3.5-turbo",
                                        input=ANY_DICT,
                                        output=ANY_DICT,
                                        metadata={"created_from": "dspy"},
                                        start_time=ANY_BUT_NONE,
                                        end_time=ANY_BUT_NONE,
                                        project_name=project_name,
                                        spans=[],
                                    ),
                                ],
                            ),
                        ],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(fake_backend.span_trees) == 1

    sort_spans_by_name(EXPECTED_TRACE_TREE.spans[0].spans[0])
    sort_spans_by_name(fake_backend.trace_trees[0].spans[0].spans[0])

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_dspy_callback__used_when_there_was_already_existing_trace_without_span__data_attached_to_existing_trace(
    fake_backend,
):
    def f():
        lm = dspy.LM(
            cache=False,
            model="openai/gpt-3.5-turbo",
        )
        dspy.configure(lm=lm)

        opik_callback = OpikCallback()
        dspy.settings.configure(callbacks=[opik_callback])

        cot = dspy.ChainOfThought("question -> answer")
        cot(question="What is the meaning of life?")

        opik_callback.flush()

    client = opik_client.get_client_cached()

    # Prepare context to have manually created trace data
    trace_data = trace.TraceData(
        name="manually-created-trace",
        input={"input": "input-of-manually-created-trace"},
    )
    context_storage.set_trace_data(trace_data)

    f()

    # Send trace data
    trace_data = context_storage.pop_trace_data()
    trace_data.init_end_time().update(
        output={"output": "output-of-manually-created-trace"}
    )
    client.trace(**trace_data.__dict__)

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_STRING(),
        name="manually-created-trace",
        input={"input": "input-of-manually-created-trace"},
        output={"output": "output-of-manually-created-trace"},
        metadata=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_STRING(),
                name="ChainOfThought",
                input={
                    "args": [],
                    "kwargs": {"question": "What is the meaning of life?"},
                },
                output=ANY_DICT,
                metadata={"created_from": "dspy"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[
                    SpanModel(
                        id=ANY_STRING(),
                        type="llm",
                        name="Predict",
                        provider=None,
                        model=None,
                        input=ANY_DICT,
                        output=ANY_DICT,
                        metadata={"created_from": "dspy"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[
                            SpanModel(
                                id=ANY_STRING(),
                                type="llm",
                                name=ANY_STRING(startswith="LM: openai"),
                                provider="openai",
                                model="gpt-3.5-turbo",
                                input=ANY_DICT,
                                output=ANY_DICT,
                                metadata={"created_from": "dspy"},
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                spans=[],
                            ),
                        ],
                    ),
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(fake_backend.span_trees) == 1

    sort_spans_by_name(EXPECTED_TRACE_TREE.spans[0])
    sort_spans_by_name(fake_backend.trace_trees[0].spans[0])

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_dspy_callback__used_when_there_was_already_existing_span_without_trace__data_attached_to_existing_span(
    fake_backend,
):
    def f():
        lm = dspy.LM(
            cache=False,
            model="openai/gpt-3.5-turbo",
        )
        dspy.configure(lm=lm)

        opik_callback = OpikCallback()
        dspy.settings.configure(callbacks=[opik_callback])

        cot = dspy.ChainOfThought("question -> answer")
        cot(question="What is the meaning of life?")

        opik_callback.flush()

    client = opik_client.get_client_cached()
    span_data = span.SpanData(
        trace_id="some-trace-id",
        name="manually-created-span",
        input={"input": "input-of-manually-created-span"},
    )
    context_storage.add_span_data(span_data)

    f()

    span_data = context_storage.pop_span_data()
    span_data.init_end_time().update(
        output={"output": "output-of-manually-created-span"}
    )
    client.span(**span_data.__dict__)
    opik.flush_tracker()

    EXPECTED_SPANS_TREE = SpanModel(
        id=ANY_STRING(),
        name="manually-created-span",
        input={"input": "input-of-manually-created-span"},
        output={"output": "output-of-manually-created-span"},
        metadata=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_STRING(),
                name="ChainOfThought",
                input={
                    "args": [],
                    "kwargs": {"question": "What is the meaning of life?"},
                },
                output=ANY_DICT,
                metadata={"created_from": "dspy"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[
                    SpanModel(
                        id=ANY_STRING(),
                        type="llm",
                        name="Predict",
                        provider=None,
                        model=None,
                        input=ANY_DICT,
                        output=ANY_DICT,
                        metadata={"created_from": "dspy"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[
                            SpanModel(
                                id=ANY_STRING(),
                                type="llm",
                                name=ANY_STRING(startswith="LM"),
                                provider="openai",
                                model="gpt-3.5-turbo",
                                input=ANY_DICT,
                                output=ANY_DICT,
                                metadata={"created_from": "dspy"},
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                spans=[],
                            ),
                        ],
                    ),
                ],
            )
        ],
    )

    assert len(fake_backend.span_trees) == 1

    sort_spans_by_name(EXPECTED_SPANS_TREE.spans[0])
    sort_spans_by_name(fake_backend.span_trees[0].spans[0])

    assert_equal(EXPECTED_SPANS_TREE, fake_backend.span_trees[0])


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("dspy-integration-test", "dspy-integration-test"),
    ],
)
def test_dspy_log_graph(
    fake_backend,
    project_name,
    expected_project_name,
):
    lm = dspy.LM(
        cache=False,
        model="openai/gpt-4o-mini",
    )
    dspy.configure(lm=lm)

    opik_callback = OpikCallback(project_name=project_name, log_graph=True)
    dspy.settings.configure(callbacks=[opik_callback])

    cot = dspy.ChainOfThought("question -> answer")
    cot(question="What is the meaning of life?")

    opik_callback.flush()

    assert "_opik_graph_definition" in fake_backend.trace_trees[0].metadata
    assert (
        fake_backend.trace_trees[0].metadata["_opik_graph_definition"]["format"]
        == "mermaid"
    )
    assert (
        fake_backend.trace_trees[0]
        .metadata["_opik_graph_definition"]["data"]
        .startswith("graph TD")
    )


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("dspy-integration-test", "dspy-integration-test"),
    ],
)
def test_dspy_no_log_graph(
    fake_backend,
    project_name,
    expected_project_name,
):
    lm = dspy.LM(
        cache=False,
        model="openai/gpt-4o-mini",
    )
    dspy.configure(lm=lm)

    opik_callback = OpikCallback(project_name=project_name)
    dspy.settings.configure(callbacks=[opik_callback])

    cot = dspy.ChainOfThought("question -> answer")
    cot(question="What is the meaning of life?")

    opik_callback.flush()

    assert "_opik_graph_definition" not in fake_backend.trace_trees[0].metadata
