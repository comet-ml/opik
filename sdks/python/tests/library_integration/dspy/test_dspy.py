from typing import Union

import uuid

import dspy
from dspy import __version__ as dspy_version
import pytest

import opik
from opik import context_storage, opik_context, semantic_version
from opik.api_objects import opik_client, span, trace
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.dspy.callback import OpikCallback
from ...testlib import (
    ANY,
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)


# Matchers using ANY_DICT.containing() as recommended in PR review
ANY_USAGE_DICT = ANY_DICT.containing(
    {
        "completion_tokens": ANY_BUT_NONE,
        "prompt_tokens": ANY_BUT_NONE,
        "total_tokens": ANY_BUT_NONE,
    }
)
ANY_METADATA_WITH_CREATED_FROM = ANY_DICT.containing({"created_from": "dspy"})


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
        id=ANY_STRING,
        name="ChainOfThought",
        input={"args": [], "kwargs": {"question": "What is the meaning of life?"}},
        output=None,
        metadata={"created_from": "dspy"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_STRING,
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
                        id=ANY_STRING,
                        type="llm",
                        name=ANY_STRING.starting_with("LM"),
                        provider="openai",
                        model=ANY_STRING.starting_with("gpt-4o-mini"),
                        input=ANY_DICT,
                        output=ANY_DICT,
                        usage=ANY_USAGE_DICT,
                        total_cost=ANY,  # Cost is extracted from DSPy history when available
                        metadata=ANY_METADATA_WITH_CREATED_FROM,
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

    # Explicitly verify that metadata contains created_from for all spans
    trace_tree = fake_backend.trace_trees[0]
    assert trace_tree.metadata["created_from"] == "dspy"
    predict_span = trace_tree.spans[0]
    assert predict_span.metadata["created_from"] == "dspy"
    lm_span = predict_span.spans[0]
    assert lm_span.metadata["created_from"] == "dspy"
    # LM span should also have usage in metadata (added when usage is set on span)
    assert "usage" in lm_span.metadata


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
        id=ANY_STRING,
        name="ChainOfThought",
        input={"args": [], "kwargs": {"question": "What is the meaning of life?"}},
        output=None,
        metadata={"created_from": "dspy"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=project_name,
        spans=[
            SpanModel(
                id=ANY_STRING,
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
                    "exception_type": ANY_STRING,
                    "message": ANY_STRING,
                    "traceback": ANY_STRING,
                },
                spans=[
                    SpanModel(
                        id=ANY_STRING,
                        type="llm",
                        name=ANY_STRING.starting_with("LM: "),
                        provider="openai",
                        model=ANY_STRING.starting_with("gpt-3.5-turbo"),
                        input=ANY_DICT,
                        output=ANY_DICT,
                        metadata={"created_from": "dspy"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        spans=[],
                        error_info={
                            "exception_type": ANY_STRING,
                            "message": ANY_STRING,
                            "traceback": ANY_STRING,
                        },
                    ),
                    SpanModel(
                        id=ANY_STRING,
                        type="llm",
                        name=ANY_STRING.starting_with("LM: "),
                        provider="openai",
                        model=ANY_STRING.starting_with("gpt-3.5-turbo"),
                        input=ANY_DICT,
                        output=ANY_DICT,
                        metadata={"created_from": "dspy"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        spans=[],
                        error_info={
                            "exception_type": ANY_STRING,
                            "message": ANY_STRING,
                            "traceback": ANY_STRING,
                        },
                    ),
                ],
            ),
        ],
    )

    if (
        semantic_version.SemanticVersion.parse(dspy_version) >= "3.0.0"
        and semantic_version.SemanticVersion.parse(dspy_version) < "3.0.4"
    ):
        EXPECTED_TRACE_TREE.spans[0].spans.append(
            SpanModel(
                id=ANY_STRING,
                type="llm",
                name=ANY_STRING.starting_with("LM: "),
                provider="openai",
                model=ANY_STRING.starting_with("gpt-3.5-turbo"),
                input=ANY_DICT,
                output=ANY_DICT,
                metadata={"created_from": "dspy"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                spans=[],
                error_info={
                    "exception_type": ANY_STRING,
                    "message": ANY_STRING,
                    "traceback": ANY_STRING,
                },
            ),
        )

    assert len(fake_backend.trace_trees) == 1
    assert len(fake_backend.span_trees) == 1

    sort_spans_by_name(EXPECTED_TRACE_TREE)
    sort_spans_by_name(fake_backend.trace_trees[0])

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])


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
        last_updated_at=ANY_BUT_NONE,
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
                        id=ANY_STRING,
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
                                id=ANY_STRING,
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
                                        id=ANY_STRING,
                                        type="llm",
                                        name=ANY_STRING.starting_with("LM: openai"),
                                        provider="openai",
                                        model=ANY_STRING.starting_with("gpt-3.5-turbo"),
                                        input=ANY_DICT,
                                        output=ANY_DICT,
                                        usage=ANY_USAGE_DICT,
                                        total_cost=ANY,  # Cost is extracted from DSPy history when available
                                        metadata=ANY_METADATA_WITH_CREATED_FROM,
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
    client.trace(**trace_data.as_parameters)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert len(fake_backend.span_trees) == 1

    # check spans directly to avoid flakiness when the LLM span is duplicated sometimes

    # check the trace is created by opik
    assert fake_backend.trace_trees[0].name == "manually-created-trace"
    assert fake_backend.trace_trees[0].input == {
        "input": "input-of-manually-created-trace"
    }
    assert fake_backend.trace_trees[0].output == {
        "output": "output-of-manually-created-trace"
    }

    # check the first span is created by dspy
    assert fake_backend.trace_trees[0].spans[0].name == "ChainOfThought"
    assert fake_backend.trace_trees[0].spans[0].input == {
        "args": [],
        "kwargs": {"question": "What is the meaning of life?"},
    }
    assert (
        fake_backend.trace_trees[0].spans[0].metadata == ANY_METADATA_WITH_CREATED_FROM
    )

    # check the second span is created by opik
    assert fake_backend.trace_trees[0].spans[0].spans[0].name == "Predict"
    assert (
        fake_backend.trace_trees[0].spans[0].spans[0].metadata
        == ANY_METADATA_WITH_CREATED_FROM
    )

    # check the last span is created by opik for LLM call
    llm_span = fake_backend.trace_trees[0].spans[0].spans[0].spans[-1]
    assert llm_span.name == ANY_STRING.starting_with("LM: openai")
    assert llm_span.type == "llm"
    assert llm_span.provider == "openai"
    assert llm_span.model == ANY_STRING.starting_with("gpt-3.5-turbo")
    assert llm_span.usage == ANY_USAGE_DICT
    assert llm_span.metadata == ANY_METADATA_WITH_CREATED_FROM


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
    client.span(**span_data.as_parameters)
    opik.flush_tracker()

    EXPECTED_SPANS_TREE = SpanModel(
        id=ANY_STRING,
        name="manually-created-span",
        input={"input": "input-of-manually-created-span"},
        output={"output": "output-of-manually-created-span"},
        metadata=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_STRING,
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
                        id=ANY_STRING,
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
                                id=ANY_STRING,
                                type="llm",
                                name=ANY_STRING.starting_with("LM"),
                                provider="openai",
                                model=ANY_STRING.starting_with("gpt-3.5-turbo"),
                                input=ANY_DICT,
                                output=ANY_DICT,
                                usage=ANY_USAGE_DICT,
                                total_cost=ANY,  # Cost is extracted from DSPy history when available
                                metadata=ANY_METADATA_WITH_CREATED_FROM,
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


def test_dspy__cache_disabled__usage_present_and_cache_hit_false(
    fake_backend,
):
    """
    When cache is disabled, LM spans should have:
    - usage data with token counts
    - cache_hit=False in metadata
    """
    lm = dspy.LM(
        cache=False,
        model="openai/gpt-4o-mini",
    )
    dspy.configure(lm=lm)

    opik_callback = OpikCallback(project_name="dspy-cache-test")
    dspy.settings.configure(callbacks=[opik_callback])

    cot = dspy.ChainOfThought("question -> answer")
    cot(question="What is the meaning of life?")

    opik_callback.flush()

    assert len(fake_backend.trace_trees) == 1

    # Find the LM span (it starts with "LM:")
    trace_tree = fake_backend.trace_trees[0]
    predict_span = trace_tree.spans[0]
    lm_span = predict_span.spans[0]

    assert lm_span.name.startswith("LM:")

    # Verify usage is present
    assert lm_span.usage is not None
    assert "prompt_tokens" in lm_span.usage
    assert "completion_tokens" in lm_span.usage
    assert "total_tokens" in lm_span.usage

    # Verify cache_hit is False
    assert lm_span.metadata.get("cache_hit") is False


def test_dspy__cache_enabled_and_response_cached__no_usage_and_cache_hit_true(
    fake_backend,
):
    """
    When cache is enabled and the response is served from cache:
    - usage should be None (no API call was made)
    - cache_hit=True in metadata
    """
    lm = dspy.LM(
        cache=True,  # Enable caching
        model="openai/gpt-4o-mini",
    )
    dspy.configure(lm=lm)

    opik_callback = OpikCallback(project_name="dspy-cache-test")
    dspy.settings.configure(callbacks=[opik_callback])

    cot = dspy.ChainOfThought("question -> answer")

    # Use a unique question to ensure we start with a non-cached response
    unique_question = f"What is {uuid.uuid4().hex[:8]}?"

    # First call - will NOT be cached (fresh question)
    cot(question=unique_question)

    # Second call with SAME question - will be cached
    cot(question=unique_question)

    opik_callback.flush()

    assert len(fake_backend.trace_trees) == 2

    # Check the second trace (cached response)
    cached_trace = fake_backend.trace_trees[1]
    cached_predict_span = cached_trace.spans[0]
    cached_lm_span = cached_predict_span.spans[0]

    assert cached_lm_span.name.startswith("LM:")

    # Verify no usage for cached response
    assert cached_lm_span.usage is None

    # Verify cache_hit is True
    assert cached_lm_span.metadata.get("cache_hit") is True


def test_dspy__cache_enabled_first_call__has_usage_and_cache_hit_false(
    fake_backend,
):
    """
    When cache is enabled but it's the first call (not yet cached):
    - usage should be present
    - cache_hit=False in metadata
    """
    lm = dspy.LM(
        cache=True,  # Enable caching
        model="openai/gpt-4o-mini",
    )
    dspy.configure(lm=lm)

    opik_callback = OpikCallback(project_name="dspy-cache-test")
    dspy.settings.configure(callbacks=[opik_callback])

    cot = dspy.ChainOfThought("question -> answer")

    # Use a unique question to ensure it's not already cached
    unique_question = f"What is {uuid.uuid4().hex[:8]}?"
    cot(question=unique_question)

    opik_callback.flush()

    assert len(fake_backend.trace_trees) == 1

    trace_tree = fake_backend.trace_trees[0]
    predict_span = trace_tree.spans[0]
    lm_span = predict_span.spans[0]

    assert lm_span.name.startswith("LM:")

    # First call should have usage
    assert lm_span.usage is not None
    assert "prompt_tokens" in lm_span.usage

    # First call should not be a cache hit
    assert lm_span.metadata.get("cache_hit") is False


def test_dspy_callback__opik_context_api_accessible_during_execution(
    fake_backend,
):
    """
    Verify that spans/traces created by DSPy callback are accessible via
    opik.opik_context API during callback execution.
    """
    captured_context = {}

    original_call = dspy.LM.__call__

    def patched_call(self, *args, **kwargs):
        captured_context["span"] = opik_context.get_current_span_data()
        captured_context["trace"] = opik_context.get_current_trace_data()
        return original_call(self, *args, **kwargs)

    dspy.LM.__call__ = patched_call

    try:
        lm = dspy.LM(cache=False, model="openai/gpt-4o-mini")
        dspy.configure(lm=lm)

        opik_callback = OpikCallback()
        dspy.settings.configure(callbacks=[opik_callback])

        cot = dspy.ChainOfThought("question -> answer")
        cot(question="What is the meaning of life?")

        opik_callback.flush()
    finally:
        dspy.LM.__call__ = original_call

    # Verify context was accessible during LM call
    assert captured_context["span"] is not None
    assert captured_context["trace"] is not None
    assert captured_context["span"].name == "Predict"
    assert captured_context["trace"].name == "ChainOfThought"

    # Verify IDs match the logged data
    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]
    assert trace_tree.id == captured_context["trace"].id
    assert trace_tree.spans[0].id == captured_context["span"].id
