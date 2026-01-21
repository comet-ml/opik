"""
Tests for thread_id resolution in OpikTracer (GitHub Issue #4794).
"""

import pytest

from langchain_core.language_models import fake
from langchain_core.prompts import PromptTemplate

from opik.integrations.langchain.opik_tracer import OpikTracer

from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    TraceModel,
    SpanModel,
    assert_equal,
)


class TestThreadIdResolution:

    def test_opik_tracer__thread_id_from_metadata__single_invocation(
        self, fake_backend
    ):
        llm = fake.FakeListLLM(responses=["response"])
        prompt = PromptTemplate(input_variables=["input"], template="{input}")
        chain = prompt | llm

        tracer = OpikTracer(project_name="test-project")
        chain.invoke(
            {"input": "test"},
            config={"callbacks": [tracer], "metadata": {"thread_id": "thread-AAA"}},
        )
        tracer.flush()

        assert len(fake_backend.trace_trees) == 1
        assert fake_backend.trace_trees[0].thread_id == "thread-AAA"

    def test_opik_tracer__thread_id_from_constructor__takes_priority(
        self, fake_backend
    ):
        llm = fake.FakeListLLM(responses=["response"])
        prompt = PromptTemplate(input_variables=["input"], template="{input}")
        chain = prompt | llm

        tracer = OpikTracer(project_name="test-project", thread_id="constructor-id")
        chain.invoke(
            {"input": "test"},
            config={"callbacks": [tracer], "metadata": {"thread_id": "metadata-id"}},
        )
        tracer.flush()

        assert len(fake_backend.trace_trees) == 1
        assert fake_backend.trace_trees[0].thread_id == "constructor-id"

    def test_opik_tracer__reused_tracer__different_thread_ids_per_invocation(
        self, fake_backend
    ):
        llm = fake.FakeListLLM(responses=["response1", "response2", "response3"])
        prompt = PromptTemplate(input_variables=["input"], template="{input}")
        chain = prompt | llm

        tracer = OpikTracer(project_name="test-project")

        chain.invoke(
            {"input": "message1"},
            config={"callbacks": [tracer], "metadata": {"thread_id": "thread-AAA"}},
        )
        chain.invoke(
            {"input": "message2"},
            config={"callbacks": [tracer], "metadata": {"thread_id": "thread-BBB"}},
        )
        chain.invoke(
            {"input": "message3"},
            config={"callbacks": [tracer], "metadata": {"thread_id": "thread-CCC"}},
        )

        tracer.flush()

        assert len(fake_backend.trace_trees) == 3

        thread_ids = [trace.thread_id for trace in fake_backend.trace_trees]
        assert "thread-AAA" in thread_ids
        assert "thread-BBB" in thread_ids
        assert "thread-CCC" in thread_ids

    def test_opik_tracer__reused_tracer_with_constructor_thread_id__all_use_constructor_value(
        self, fake_backend
    ):
        llm = fake.FakeListLLM(responses=["response1", "response2"])
        prompt = PromptTemplate(input_variables=["input"], template="{input}")
        chain = prompt | llm

        tracer = OpikTracer(project_name="test-project", thread_id="fixed-thread")

        chain.invoke(
            {"input": "message1"},
            config={"callbacks": [tracer], "metadata": {"thread_id": "ignored-AAA"}},
        )
        chain.invoke(
            {"input": "message2"},
            config={"callbacks": [tracer], "metadata": {"thread_id": "ignored-BBB"}},
        )

        tracer.flush()

        assert len(fake_backend.trace_trees) == 2
        assert fake_backend.trace_trees[0].thread_id == "fixed-thread"
        assert fake_backend.trace_trees[1].thread_id == "fixed-thread"

    def test_opik_tracer__no_thread_id__traces_have_no_thread_id(
        self, fake_backend
    ):
        llm = fake.FakeListLLM(responses=["response"])
        prompt = PromptTemplate(input_variables=["input"], template="{input}")
        chain = prompt | llm

        tracer = OpikTracer(project_name="test-project")
        chain.invoke(
            {"input": "test"},
            config={"callbacks": [tracer]},
        )
        tracer.flush()

        assert len(fake_backend.trace_trees) == 1
        assert fake_backend.trace_trees[0].thread_id is None

    def test_opik_tracer__thread_id_in_metadata_is_preserved_in_trace_metadata(
        self, fake_backend
    ):
        llm = fake.FakeListLLM(responses=["response"])
        prompt = PromptTemplate(input_variables=["input"], template="{input}")
        chain = prompt | llm

        tracer = OpikTracer(project_name="test-project")
        chain.invoke(
            {"input": "test"},
            config={
                "callbacks": [tracer],
                "metadata": {"thread_id": "thread-123", "other_key": "other_value"},
            },
        )
        tracer.flush()

        assert len(fake_backend.trace_trees) == 1
        trace = fake_backend.trace_trees[0]

        assert trace.thread_id == "thread-123"
        assert trace.metadata.get("thread_id") == "thread-123"
        assert trace.metadata.get("other_key") == "other_value"
