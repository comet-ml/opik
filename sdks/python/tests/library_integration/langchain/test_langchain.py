import mock
import os
from opik.message_processing import streamer_constructors
from ...testlib import backend_emulator_message_processor
from ...testlib import (
    SpanModel,
    TraceModel,
    ANY_BUT_NONE,
    ANY_DICT,
    assert_equal,
)
import pytest
import opik
from opik.api_objects import opik_client, trace, span
from opik import context_storage
from langchain.llms import openai as langchain_openai
from langchain.llms import fake

from langchain.prompts import PromptTemplate
from opik.integrations.langchain.opik_tracer import OpikTracer


@pytest.fixture()
def ensure_openai_configured():
    # don't use assertion here to prevent printing os.environ with all env variables

    if not ("OPENAI_API_KEY" in os.environ and "OPENAI_ORG_ID" in os.environ):
        raise Exception("OpenAI not configured!")


def test_langchain__happyflow(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):
        llm = fake.FakeListLLM(
            responses=[
                "I'm sorry, I don't think I'm talented enough to write a synopsis"
            ]
        )

        template = "Given the title of play, right a synopsys for that. Title: {title}."

        prompt_template = PromptTemplate(input_variables=["title"], template=template)

        synopsis_chain = prompt_template | llm
        test_prompts = {"title": "Documentary about Bigfoot in Paris"}

        callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})
        synopsis_chain.invoke(input=test_prompts, config={"callbacks": [callback]})

        callback.flush()
        mock_construct_online_streamer.assert_called_once()

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
                                    "Given the title of play, right a synopsys for that. Title: Documentary about Bigfoot in Paris."
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

        assert len(fake_message_processor_.trace_trees) == 1
        assert len(callback.created_traces()) == 1
        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_langchain__openai_llm_is_used__token_usage_is_logged__happyflow(
    fake_streamer, ensure_openai_configured
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):
        llm = langchain_openai.OpenAI(max_tokens=10, name="custom-openai-llm-name")

        template = "Given the title of play, right a synopsys for that. Title: {title}."

        prompt_template = PromptTemplate(input_variables=["title"], template=template)

        synopsis_chain = prompt_template | llm
        test_prompts = {"title": "Documentary about Bigfoot in Paris"}

        callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})
        synopsis_chain.invoke(input=test_prompts, config={"callbacks": [callback]})

        callback.flush()
        mock_construct_online_streamer.assert_called_once()

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
                            input={
                                "prompts": [
                                    "Given the title of play, right a synopsys for that. Title: Documentary about Bigfoot in Paris."
                                ]
                            },
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
                        ),
                    ],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1
        assert len(callback.created_traces()) == 1
        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_langchain_callback__used_inside_another_track_function__data_attached_to_existing_trace_tree(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):
        callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})

        @opik.track(capture_output=True)
        def f(x):
            llm = fake.FakeListLLM(
                responses=[
                    "I'm sorry, I don't think I'm talented enough to write a synopsis"
                ]
            )

            template = (
                "Given the title of play, right a synopsys for that. Title: {title}."
            )

            prompt_template = PromptTemplate(
                input_variables=["title"], template=template
            )

            synopsis_chain = prompt_template | llm
            test_prompts = {"title": "Documentary about Bigfoot in Paris"}

            synopsis_chain.invoke(input=test_prompts, config={"callbacks": [callback]})

            return "the-output"

        f("the-input")
        opik.flush_tracker()

        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="f",
            input={"x": "the-input"},
            output={"output": "the-output"},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="f",
                    input={"x": "the-input"},
                    output={"output": "the-output"},
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
                                    input={
                                        "title": "Documentary about Bigfoot in Paris"
                                    },
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
                                            "Given the title of play, right a synopsys for that. Title: Documentary about Bigfoot in Paris."
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
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1
        assert len(callback.created_traces()) == 0
        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_langchain_callback__used_when_there_was_already_existing_trace_without_span__data_attached_to_existing_trace(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):
        callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})

        def f():
            llm = fake.FakeListLLM(
                responses=[
                    "I'm sorry, I don't think I'm talented enough to write a synopsis"
                ]
            )

            template = (
                "Given the title of play, right a synopsys for that. Title: {title}."
            )

            prompt_template = PromptTemplate(
                input_variables=["title"], template=template
            )

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

        mock_construct_online_streamer.assert_called_once()

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
                                    "Given the title of play, right a synopsys for that. Title: Documentary about Bigfoot in Paris."
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

        assert len(fake_message_processor_.trace_trees) == 1
        assert len(callback.created_traces()) == 0

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_langchain_callback__used_when_there_was_already_existing_span_without_trace__data_attached_to_existing_span(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):
        callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})

        def f():
            llm = fake.FakeListLLM(
                responses=[
                    "I'm sorry, I don't think I'm talented enough to write a synopsis"
                ]
            )

            template = (
                "Given the title of play, right a synopsys for that. Title: {title}."
            )

            prompt_template = PromptTemplate(
                input_variables=["title"], template=template
            )

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

        mock_construct_online_streamer.assert_called_once()

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
                                    "Given the title of play, right a synopsys for that. Title: Documentary about Bigfoot in Paris."
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

        assert len(fake_message_processor_.span_trees) == 1
        assert len(callback.created_traces()) == 0
        assert_equal(EXPECTED_SPANS_TREE, fake_message_processor_.span_trees[0])
