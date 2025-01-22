import langchain_google_vertexai
import langchain_openai
import pytest
from langchain.llms import fake
from langchain.prompts import PromptTemplate

import opik
from opik import context_storage
from opik.api_objects import opik_client, span, trace
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.langchain.opik_tracer import OpikTracer
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
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
        metadata={"a": "b"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="RunnableSequence",
                input={"title": "Documentary about Bigfoot in Paris"},
                output=ANY_DICT,
                tags=["tag1", "tag2"],
                metadata={"a": "b"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="general",
                        name="PromptTemplate",
                        input={"title": "Documentary about Bigfoot in Paris"},
                        output=ANY_DICT,
                        metadata={},
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
                        metadata={
                            "invocation_params": {
                                "responses": [
                                    "I'm sorry, I don't think I'm talented enough to write a synopsis"
                                ],
                                "_type": "fake-list",
                                "stop": None,
                            },
                            "options": {"stop": None},
                            "batch_size": 1,
                            "metadata": ANY_BUT_NONE,
                        },
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=expected_project_name,
                        spans=[],
                    ),
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(callback.created_traces()) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@pytest.mark.parametrize(
    "llm_model, expected_input_prompt",
    [
        (
            langchain_openai.OpenAI,
            "Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris.",
        ),
        (
            langchain_openai.ChatOpenAI,
            "Human: Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris.",
        ),
    ],
)
def test_langchain__openai_llm_is_used__token_usage_is_logged__happyflow(
    fake_backend, ensure_openai_configured, llm_model, expected_input_prompt
):
    llm = llm_model(max_tokens=10, name="custom-openai-llm-name")

    template = "Given the title of play, write a synopsys for that. Title: {title}."

    prompt_template = PromptTemplate(input_variables=["title"], template=template)

    synopsis_chain = prompt_template | llm
    test_prompts = {"title": "Documentary about Bigfoot in Paris"}

    callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})
    synopsis_chain.invoke(input=test_prompts, config={"callbacks": [callback]})

    callback.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="RunnableSequence",
        input={"title": "Documentary about Bigfoot in Paris"},
        output=ANY_BUT_NONE,
        tags=["tag1", "tag2"],
        metadata={"a": "b"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="RunnableSequence",
                input={"title": "Documentary about Bigfoot in Paris"},
                output=ANY_BUT_NONE,
                tags=["tag1", "tag2"],
                metadata={"a": "b"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="general",
                        name="PromptTemplate",
                        input={"title": "Documentary about Bigfoot in Paris"},
                        output={"output": ANY_BUT_NONE},
                        metadata={},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="custom-openai-llm-name",
                        input={"prompts": [expected_input_prompt]},
                        output=ANY_BUT_NONE,
                        metadata=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        usage={
                            "completion_tokens": ANY_BUT_NONE,
                            "prompt_tokens": ANY_BUT_NONE,
                            "total_tokens": ANY_BUT_NONE,
                        },
                        spans=[],
                        provider="openai",
                        model=ANY_STRING(startswith="gpt-3.5-turbo"),
                    ),
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(callback.created_traces()) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@pytest.mark.skip
@pytest.mark.parametrize(
    "llm_model, expected_input_prompt, metadata_usage",
    [
        (
            langchain_google_vertexai.VertexAI,
            "Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris.",
            {
                # openai format
                "completion_tokens": ANY_BUT_NONE,
                "prompt_tokens": ANY_BUT_NONE,
                "total_tokens": ANY_BUT_NONE,
                # VertexAI format
                # "cached_content_token_count": ANY_BUT_NONE,
                "candidates_token_count": ANY_BUT_NONE,
                "prompt_token_count": ANY_BUT_NONE,
                "total_token_count": ANY_BUT_NONE,
            },
        ),
        (
            langchain_google_vertexai.ChatVertexAI,
            "Human: Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris.",
            {
                # openai format
                "completion_tokens": ANY_BUT_NONE,
                "prompt_tokens": ANY_BUT_NONE,
                "total_tokens": ANY_BUT_NONE,
                # ChatVertexAI format
                "cached_content_token_count": ANY_BUT_NONE,
                "candidates_token_count": ANY_BUT_NONE,
                "prompt_token_count": ANY_BUT_NONE,
                "total_token_count": ANY_BUT_NONE,
            },
        ),
    ],
)
def test_langchain__google_vertexai_llm_is_used__token_usage_is_logged__happyflow(
    fake_backend,
    gcp_e2e_test_credentials,
    llm_model,
    expected_input_prompt,
    metadata_usage,
):
    llm = llm_model(
        max_tokens=10,
        model_name="gemini-1.5-flash",
        name="custom-google-vertexai-llm-name",
    )

    template = "Given the title of play, write a synopsys for that. Title: {title}."

    prompt_template = PromptTemplate(input_variables=["title"], template=template)

    synopsis_chain = prompt_template | llm
    test_prompts = {"title": "Documentary about Bigfoot in Paris"}

    callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})
    synopsis_chain.invoke(input=test_prompts, config={"callbacks": [callback]})

    callback.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="RunnableSequence",
        input={"title": "Documentary about Bigfoot in Paris"},
        output=ANY_BUT_NONE,
        tags=["tag1", "tag2"],
        metadata={"a": "b"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="RunnableSequence",
                input={"title": "Documentary about Bigfoot in Paris"},
                output=ANY_BUT_NONE,
                tags=["tag1", "tag2"],
                metadata={"a": "b"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="general",
                        name="PromptTemplate",
                        input={"title": "Documentary about Bigfoot in Paris"},
                        output={"output": ANY_BUT_NONE},
                        metadata={},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="custom-google-vertexai-llm-name",
                        input={"prompts": [expected_input_prompt]},
                        output=ANY_BUT_NONE,
                        metadata={
                            "batch_size": ANY_BUT_NONE,
                            "invocation_params": ANY_DICT,
                            "metadata": ANY_DICT,
                            "options": ANY_DICT,
                            "usage": metadata_usage,
                        },
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        usage={
                            # only openai format
                            "completion_tokens": ANY_BUT_NONE,
                            "prompt_tokens": ANY_BUT_NONE,
                            "total_tokens": ANY_BUT_NONE,
                        },
                        spans=[],
                        provider="google_vertexai",
                        model=ANY_STRING(startswith="gemini-1.5-flash"),
                    ),
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(callback.created_traces()) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_langchain__openai_llm_is_used__error_occurred_during_openai_call__error_info_is_logged(
    fake_backend,
):
    llm = langchain_openai.OpenAI(
        max_tokens=10, name="custom-openai-llm-name", api_key="incorrect-api-key"
    )

    template = "Given the title of play, write a synopsys for that. Title: {title}."

    prompt_template = PromptTemplate(input_variables=["title"], template=template)

    synopsis_chain = prompt_template | llm
    test_prompts = {"title": "Documentary about Bigfoot in Paris"}

    callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})
    with pytest.raises(Exception):
        synopsis_chain.invoke(input=test_prompts, config={"callbacks": [callback]})

    callback.flush()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="RunnableSequence",
        input={"title": "Documentary about Bigfoot in Paris"},
        output=None,
        tags=["tag1", "tag2"],
        metadata={"a": "b"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        error_info={
            "exception_type": ANY_STRING(),
            "traceback": ANY_STRING(),
        },
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="RunnableSequence",
                input={"title": "Documentary about Bigfoot in Paris"},
                output=None,
                tags=["tag1", "tag2"],
                metadata={"a": "b"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                error_info={
                    "exception_type": ANY_STRING(),
                    "traceback": ANY_STRING(),
                },
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="general",
                        name="PromptTemplate",
                        input={"title": "Documentary about Bigfoot in Paris"},
                        output={"output": ANY_BUT_NONE},
                        metadata={},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="custom-openai-llm-name",
                        input={
                            "prompts": [
                                "Given the title of play, write a synopsys for that. Title: Documentary about Bigfoot in Paris."
                            ]
                        },
                        output=None,
                        metadata=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        usage=None,
                        error_info={
                            "exception_type": ANY_STRING(),
                            "traceback": ANY_STRING(),
                        },
                        spans=[],
                    ),
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert len(callback.created_traces()) == 1
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
                        metadata={"a": "b"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        spans=[
                            SpanModel(
                                id=ANY_BUT_NONE,
                                type="general",
                                name="PromptTemplate",
                                input={"title": "Documentary about Bigfoot in Paris"},
                                output={"output": ANY_BUT_NONE},
                                metadata={},
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
                                metadata={
                                    "invocation_params": {
                                        "responses": [
                                            "I'm sorry, I don't think I'm talented enough to write a synopsis"
                                        ],
                                        "_type": "fake-list",
                                        "stop": None,
                                    },
                                    "options": {"stop": None},
                                    "batch_size": 1,
                                    "metadata": ANY_BUT_NONE,
                                },
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
    client.trace(**trace_data.__dict__)

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="manually-created-trace",
        input={"input": "input-of-manually-created-trace"},
        output={"output": "output-of-manually-created-trace"},
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
                metadata={"a": "b"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="general",
                        name="PromptTemplate",
                        input={"title": "Documentary about Bigfoot in Paris"},
                        output=ANY_DICT,
                        metadata={},
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
                        metadata={
                            "invocation_params": {
                                "responses": [
                                    "I'm sorry, I don't think I'm talented enough to write a synopsis"
                                ],
                                "_type": "fake-list",
                                "stop": None,
                            },
                            "options": {"stop": None},
                            "batch_size": 1,
                            "metadata": ANY_BUT_NONE,
                        },
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
    client.span(**span_data.__dict__)
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
                metadata={"a": "b"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="general",
                        name="PromptTemplate",
                        input={"title": "Documentary about Bigfoot in Paris"},
                        output={"output": ANY_BUT_NONE},
                        metadata={},
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
                        metadata={
                            "invocation_params": {
                                "responses": [
                                    "I'm sorry, I don't think I'm talented enough to write a synopsis"
                                ],
                                "_type": "fake-list",
                                "stop": None,
                            },
                            "options": {"stop": None},
                            "batch_size": 1,
                            "metadata": ANY_BUT_NONE,
                        },
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
