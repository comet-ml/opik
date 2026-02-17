import pytest
from langchain_core.language_models import fake
from langchain_core.language_models.fake import FakeStreamingListLLM
from langchain_core.prompts import PromptTemplate
from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

import opik
from opik import context_storage
from opik.api_objects import opik_client, span, trace
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.langchain.opik_tracer import OpikTracer, ERROR_SKIPPED_OUTPUTS
from opik.types import DistributedTraceHeadersDict

from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
    patch_environ,
)


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("langchain-integration-test", "langchain-integration-test"),
    ],
)
def test_langchain__happyflow(
    fake_backend,
    project_name,
    expected_project_name,
):
    llm = fake.FakeListLLM(
        responses=["I'm sorry, I don't think I'm talented enough to write a synopsis"]
    )

    template = "Given the title of play, write a synopsys for that. Title: {title}."

    prompt_template = PromptTemplate(input_variables=["title"], template=template)

    synopsis_chain = prompt_template | llm
    test_prompts = {"title": "Documentary about Bigfoot in Paris"}

    callback = OpikTracer(
        project_name=project_name, tags=["tag1", "tag2"], metadata={"a": "b"}
    )
    synopsis_chain.invoke(input=test_prompts, config={"callbacks": [callback]})

    callback.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="RunnableSequence",
        input={"title": "Documentary about Bigfoot in Paris"},
        output={
            "output": "I'm sorry, I don't think I'm talented enough to write a synopsis"
        },
        tags=["tag1", "tag2"],
        metadata={
            "a": "b",
            "created_from": "langchain",
        },
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="tool",
                name="PromptTemplate",
                input={"title": "Documentary about Bigfoot in Paris"},
                output=ANY_DICT,
                metadata={
                    "created_from": "langchain",
                },
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[],
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="FakeListLLM",
                input={
                    "prompts": [
                        "Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris."
                    ]
                },
                output=ANY_DICT,
                metadata=ANY_DICT.containing({"created_from": "langchain"}),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[],
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(callback.created_traces()) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_langchain__distributed_headers__happyflow(
    fake_backend,
):
    project_name = "langchain-integration-test--distributed-headers"
    client = opik_client.get_client_cached()

    # PREPARE DISTRIBUTED HEADERS
    trace_data = trace.TraceData(
        name="custom-distributed-headers--trace",
        input={
            "key1": 1,
            "key2": "val2",
        },
        project_name=project_name,
        tags=["tag_d1", "tag_d2"],
    )
    trace_data.init_end_time()
    client.trace(**trace_data.as_parameters)

    span_data = span.SpanData(
        trace_id=trace_data.id,
        parent_span_id=None,
        name="custom-distributed-headers--span",
        input={
            "input": "custom-distributed-headers--input",
        },
        project_name=project_name,
        tags=["tag_d3", "tag_d4"],
    )
    span_data.init_end_time().update(
        output={"output": "custom-distributed-headers--output"},
    )
    client.span(**span_data.as_parameters)

    distributed_headers = DistributedTraceHeadersDict(
        opik_trace_id=span_data.trace_id,
        opik_parent_span_id=span_data.id,
    )

    # CALL LLM
    llm = fake.FakeListLLM(
        responses=["I'm sorry, I don't think I'm talented enough to write a synopsis"]
    )

    template = "Given the title of play, write a synopsys for that. Title: {title}."

    prompt_template = PromptTemplate(input_variables=["title"], template=template)

    synopsis_chain = prompt_template | llm
    test_prompts = {"title": "Documentary about Bigfoot in Paris"}

    callback = OpikTracer(
        project_name=project_name,
        tags=["tag1", "tag2"],
        metadata={"a": "b"},
        distributed_headers=distributed_headers,
    )
    synopsis_chain.invoke(input=test_prompts, config={"callbacks": [callback]})

    callback.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="custom-distributed-headers--trace",
        input={"key1": 1, "key2": "val2"},
        output=None,
        tags=["tag_d1", "tag_d2"],
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="custom-distributed-headers--span",
                input={"input": "custom-distributed-headers--input"},
                output={"output": "custom-distributed-headers--output"},
                tags=["tag_d3", "tag_d4"],
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="RunnableSequence",
                        input={"title": "Documentary about Bigfoot in Paris"},
                        output=ANY_DICT,
                        tags=["tag1", "tag2"],
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        metadata={
                            "a": "b",
                            "created_from": "langchain",
                        },
                        project_name=project_name,
                        spans=[
                            SpanModel(
                                id=ANY_BUT_NONE,
                                type="tool",
                                name="PromptTemplate",
                                input={"title": "Documentary about Bigfoot in Paris"},
                                output=ANY_DICT,
                                metadata={
                                    "created_from": "langchain",
                                },
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                project_name=project_name,
                                spans=[],
                            ),
                            SpanModel(
                                id=ANY_BUT_NONE,
                                type="llm",
                                name="FakeListLLM",
                                input={
                                    "prompts": [
                                        "Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris."
                                    ]
                                },
                                output=ANY_DICT,
                                metadata=ANY_DICT.containing(
                                    {"created_from": "langchain"}
                                ),
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                project_name=project_name,
                                spans=[],
                            ),
                        ],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(callback.created_traces()) == 0
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_langchain_callback__used_inside_another_track_function__data_attached_to_existing_trace_tree(
    fake_backend,
):
    project_name = "langchain-integration-test"

    callback = OpikTracer(
        # we are trying to log span into another project, but parent's project name will be used
        project_name="langchain-integration-test-nested-level",
        tags=["tag1", "tag2"],
        metadata={"a": "b"},
    )

    @opik.track(project_name=project_name, capture_output=True)
    def f(x):
        llm = fake.FakeListLLM(
            responses=[
                "I'm sorry, I don't think I'm talented enough to write a synopsis"
            ]
        )

        template = "Given the title of play, write a synopsys for that. Title: {title}."

        prompt_template = PromptTemplate(input_variables=["title"], template=template)

        synopsis_chain = prompt_template | llm
        test_prompts = {"title": "Documentary about Bigfoot in Paris"}

        synopsis_chain.invoke(input=test_prompts, config={"callbacks": [callback]})

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
                input={"x": "the-input"},
                output={"output": "the-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="RunnableSequence",
                        input={"title": "Documentary about Bigfoot in Paris"},
                        output={
                            "output": "I'm sorry, I don't think I'm talented enough to write a synopsis"
                        },
                        tags=["tag1", "tag2"],
                        metadata={
                            "a": "b",
                            "created_from": "langchain",
                        },
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        spans=[
                            SpanModel(
                                id=ANY_BUT_NONE,
                                type="tool",
                                name="PromptTemplate",
                                input={"title": "Documentary about Bigfoot in Paris"},
                                output={"output": ANY_BUT_NONE},
                                metadata={
                                    "created_from": "langchain",
                                },
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                project_name=project_name,
                                spans=[],
                            ),
                            SpanModel(
                                id=ANY_BUT_NONE,
                                type="llm",
                                name="FakeListLLM",
                                input={
                                    "prompts": [
                                        "Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris."
                                    ]
                                },
                                output=ANY_DICT,
                                metadata=ANY_DICT.containing(
                                    {"created_from": "langchain"}
                                ),
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                project_name=project_name,
                                spans=[],
                            ),
                        ],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(callback.created_traces()) == 0
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_langchain_callback__used_when_there_was_already_existing_trace_without_span__data_attached_to_existing_trace(
    fake_backend,
):
    callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})

    def f():
        llm = fake.FakeListLLM(
            responses=[
                "I'm sorry, I don't think I'm talented enough to write a synopsis"
            ]
        )

        template = "Given the title of play, write a synopsys for that. Title: {title}."

        prompt_template = PromptTemplate(input_variables=["title"], template=template)

        synopsis_chain = prompt_template | llm
        test_prompts = {"title": "Documentary about Bigfoot in Paris"}

        synopsis_chain.invoke(input=test_prompts, config={"callbacks": [callback]})

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

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="manually-created-trace",
        input={"input": "input-of-manually-created-trace"},
        output={"output": "output-of-manually-created-trace"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="RunnableSequence",
                input={"title": "Documentary about Bigfoot in Paris"},
                output={
                    "output": "I'm sorry, I don't think I'm talented enough to write a synopsis"
                },
                tags=["tag1", "tag2"],
                metadata={
                    "a": "b",
                    "created_from": "langchain",
                },
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="tool",
                        name="PromptTemplate",
                        input={"title": "Documentary about Bigfoot in Paris"},
                        output=ANY_DICT,
                        metadata={
                            "created_from": "langchain",
                        },
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="FakeListLLM",
                        input={
                            "prompts": [
                                "Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris."
                            ]
                        },
                        output=ANY_DICT,
                        metadata=ANY_DICT.containing({"created_from": "langchain"}),
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    ),
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(callback.created_traces()) == 0

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_langchain_callback__used_when_there_was_already_existing_span_without_trace__data_attached_to_existing_span(
    fake_backend,
):
    callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})

    def f():
        llm = fake.FakeListLLM(
            responses=[
                "I'm sorry, I don't think I'm talented enough to write a synopsis"
            ]
        )

        template = "Given the title of play, write a synopsys for that. Title: {title}."

        prompt_template = PromptTemplate(input_variables=["title"], template=template)

        synopsis_chain = prompt_template | llm
        test_prompts = {"title": "Documentary about Bigfoot in Paris"}

        synopsis_chain.invoke(input=test_prompts, config={"callbacks": [callback]})

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
        id=ANY_BUT_NONE,
        name="manually-created-span",
        input={"input": "input-of-manually-created-span"},
        output={"output": "output-of-manually-created-span"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="RunnableSequence",
                input={"title": "Documentary about Bigfoot in Paris"},
                output={
                    "output": "I'm sorry, I don't think I'm talented enough to write a synopsis"
                },
                tags=["tag1", "tag2"],
                metadata={
                    "a": "b",
                    "created_from": "langchain",
                },
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="tool",
                        name="PromptTemplate",
                        input={"title": "Documentary about Bigfoot in Paris"},
                        output={"output": ANY_BUT_NONE},
                        metadata={
                            "created_from": "langchain",
                        },
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="FakeListLLM",
                        input={
                            "prompts": [
                                "Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris."
                            ]
                        },
                        output=ANY_DICT,
                        metadata=ANY_DICT.containing({"created_from": "langchain"}),
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    ),
                ],
            )
        ],
    )

    assert len(fake_backend.span_trees) == 1
    assert len(callback.created_traces()) == 0
    assert_equal(EXPECTED_SPANS_TREE, fake_backend.span_trees[0])


def test_langchain_callback__disabled_tracking(fake_backend):
    with patch_environ({"OPIK_TRACK_DISABLE": "true"}):
        llm = fake.FakeListLLM(
            responses=[
                "I'm sorry, I don't think I'm talented enough to write a synopsis"
            ]
        )

        template = "Given the title of play, write a synopsys for that. Title: {title}."

        prompt_template = PromptTemplate(input_variables=["title"], template=template)

        synopsis_chain = prompt_template | llm
        test_prompts = {"title": "Documentary about Bigfoot in Paris"}

        callback = OpikTracer()
        synopsis_chain.invoke(input=test_prompts, config={"callbacks": [callback]})

        callback.flush()

        assert len(fake_backend.trace_trees) == 0
        assert len(callback.created_traces()) == 0


def test_langchain_callback__skip_error_callback__error_output_skipped(
    fake_backend,
):
    def _should_skip_error(error: str) -> bool:
        if error is not None and error.startswith("FakeListLLMError"):
            # skip processing - we are sure that this is OK
            return True
        else:
            return False

    callback = OpikTracer(
        skip_error_callback=_should_skip_error,
    )

    llm = FakeStreamingListLLM(
        error_on_chunk_number=0,  # throw error on the first chunk
        responses=["I'm sorry, I don't think I'm talented enough to write a synopsis"],
    )

    template = "Given the title of play, write a synopsis for that. Title: {title}."
    prompt_template = PromptTemplate(input_variables=["title"], template=template)

    synopsis_chain = prompt_template | llm
    test_prompts = {"title": "Documentary about Bigfoot in Paris"}

    stream = synopsis_chain.stream(
        input=test_prompts, config=RunnableConfig(callbacks=[callback])
    )
    try:
        for p in stream:
            print(p)
    except Exception:
        # ignoring exception
        pass

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="RunnableSequence",
        project_name="Default Project",
        input={"title": "Documentary about Bigfoot in Paris"},
        output=ERROR_SKIPPED_OUTPUTS,
        metadata={"created_from": "langchain"},
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="PromptTemplate",
                input={"title": "Documentary about Bigfoot in Paris"},
                output={"output": ANY_DICT},
                metadata={"created_from": "langchain"},
                type="tool",
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
                last_updated_at=ANY_BUT_NONE,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="FakeStreamingListLLM",
                input={"prompts": ANY_BUT_NONE},
                output=ANY_DICT,
                tags=None,
                metadata=ANY_DICT,
                type="llm",
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
                last_updated_at=ANY_BUT_NONE,
            ),
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])


def test_langchain__tool_with_description__description_attached_to_span_metadata(
    fake_backend,
):
    """Test that tool description/docstring is attached to the tool span metadata."""

    @tool
    def get_weather(location: str) -> str:
        """Fetches the current weather for a given location."""
        return f"The weather in {location} is sunny and 25°C."

    llm = fake.FakeListLLM(responses=["The weather is nice today!"])
    prompt_template = PromptTemplate(
        input_variables=["input"],
        template="Summarize this weather: {input}",
    )

    # Create a chain: tool -> prompt -> llm
    chain = get_weather | prompt_template | llm

    callback = OpikTracer()
    _ = chain.invoke("Paris", config={"callbacks": [callback]})

    callback.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="RunnableSequence",
        input={"input": "Paris"},
        output={"output": "The weather is nice today!"},
        metadata={"created_from": "langchain"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="tool",
                name="get_weather",
                input={"input": "Paris"},
                output={"output": "The weather in Paris is sunny and 25°C."},
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "langchain",
                        "tool_description": "Fetches the current weather for a given location.",
                    }
                ),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                type="tool",
                name="PromptTemplate",
                input={"input": "The weather in Paris is sunny and 25°C."},
                output=ANY_DICT,
                metadata={"created_from": "langchain"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="FakeListLLM",
                input={
                    "prompts": [
                        "Summarize this weather: The weather in Paris is sunny and 25°C."
                    ]
                },
                output=ANY_DICT,
                metadata=ANY_DICT.containing({"created_from": "langchain"}),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(callback.created_traces()) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
