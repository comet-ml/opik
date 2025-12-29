"""
Test for token tracking in agent workflows with tool calls.

This test verifies that tool calls do not incorrectly contribute to token usage,
which was causing token duplication in agentic trajectories.

See: https://github.com/comet-ml/opik/issues/4574
"""

import pytest
from langchain.agents import create_tool_calling_agent, AgentExecutor
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.tools import tool

from opik.integrations.langchain import OpikTracer
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)


@tool
def get_weather(city: str) -> str:
    """Get weather for a given city."""
    return f"{city} - 28 °C"


@pytest.mark.parametrize("use_streaming", [False, True])
def test_agent_tool_calls_do_not_duplicate_tokens(
    fake_backend,
    ensure_openai_configured,
    use_streaming,
):
    """
    Test that tool calls in an agent workflow do not have usage data,
    preventing token duplication when backend aggregates across spans.
    
    This test:
    1. Creates an agent with a tool
    2. Executes the agent which will make an LLM call followed by a tool call
    3. Verifies that only LLM spans have usage data
    4. Verifies that tool spans do NOT have usage data
    """
    from langchain_openai import ChatOpenAI

    # Configure LLM
    llm_args = {
        "model": "gpt-4o-mini",
        "temperature": 0,
        "max_tokens": 50,
    }
    if use_streaming:
        llm_args["stream_usage"] = True

    llm = ChatOpenAI(**llm_args)

    # Create agent with tool
    prompt = ChatPromptTemplate.from_messages(
        [
            ("system", "You are a helpful assistant"),
            ("human", "{input}"),
            ("placeholder", "{agent_scratchpad}"),
        ]
    )

    agent = create_tool_calling_agent(llm, [get_weather], prompt)
    agent_executor = AgentExecutor(agent=agent, tools=[get_weather], verbose=True)

    # Execute agent with tracer
    opik_tracer = OpikTracer(tags=["test-agent"], project_name="test-agent-tokens")
    
    question = "what is the weather in sf"
    agent_executor.invoke(
        {"input": question},
        config={"callbacks": [opik_tracer]}
    )
    
    opik_tracer.flush()
    
    # Get all logged spans
    logged_spans = fake_backend.span_trees

    # Verify we have spans
    assert len(logged_spans) > 0, "Expected at least one span to be logged"
    
    # Find LLM and tool spans
    llm_spans = []
    tool_spans = []
    
    def collect_spans(span_tree):
        """Recursively collect all spans from the tree"""
        if span_tree.type == "llm":
            llm_spans.append(span_tree)
        elif span_tree.type == "tool":
            tool_spans.append(span_tree)
        
        # Process children
        for child in span_tree.spans:
            collect_spans(child)
    
    for span_tree in logged_spans:
        collect_spans(span_tree)
    
    # Verify we have both LLM and tool spans
    assert len(llm_spans) > 0, "Expected at least one LLM span"
    assert len(tool_spans) > 0, "Expected at least one tool span"
    
    # Critical assertion: LLM spans should have usage data
    for llm_span in llm_spans:
        assert llm_span.usage is not None, f"LLM span {llm_span.name} should have usage data"
        assert "prompt_tokens" in llm_span.usage, f"LLM span {llm_span.name} should have prompt_tokens"
        assert "completion_tokens" in llm_span.usage, f"LLM span {llm_span.name} should have completion_tokens"
        assert "total_tokens" in llm_span.usage, f"LLM span {llm_span.name} should have total_tokens"
        assert llm_span.usage["total_tokens"] > 0, f"LLM span {llm_span.name} should have non-zero tokens"
    
    # Critical assertion: Tool spans should NOT have usage data
    for tool_span in tool_spans:
        # Tool spans should either have no usage field or empty usage
        if tool_span.usage is not None:
            # If usage exists, it should be empty or have all zero values
            assert (
                not tool_span.usage 
                or all(v == 0 for v in tool_span.usage.values() if isinstance(v, (int, float)))
            ), f"Tool span {tool_span.name} should not have token usage data, but got: {tool_span.usage}"


@pytest.mark.parametrize("use_streaming", [False, True])
def test_agent_total_token_count_excludes_tool_calls(
    fake_backend,
    ensure_openai_configured,
    use_streaming,
):
    """
    Test that total token count aggregation excludes tool call tokens.
    
    This simulates what the backend does when it sums up usage across spans.
    We verify that tool spans don't contribute to the total.
    """
    from langchain_openai import ChatOpenAI

    # Configure LLM
    llm_args = {
        "model": "gpt-4o-mini",
        "temperature": 0,
        "max_tokens": 50,
    }
    if use_streaming:
        llm_args["stream_usage"] = True

    llm = ChatOpenAI(**llm_args)

    # Create agent with tool
    prompt = ChatPromptTemplate.from_messages(
        [
            ("system", "You are a helpful assistant"),
            ("human", "{input}"),
            ("placeholder", "{agent_scratchpad}"),
        ]
    )

    agent = create_tool_calling_agent(llm, [get_weather], prompt)
    agent_executor = AgentExecutor(agent=agent, tools=[get_weather], verbose=True)

    # Execute agent with tracer
    opik_tracer = OpikTracer(tags=["test-agent"], project_name="test-agent-tokens")
    
    question = "what is the weather in sf"
    agent_executor.invoke(
        {"input": question},
        config={"callbacks": [opik_tracer]}
    )
    
    opik_tracer.flush()
    
    # Get all logged spans
    logged_spans = fake_backend.span_trees

    # Simulate backend aggregation: sum up all token counts
    total_prompt_tokens = 0
    total_completion_tokens = 0
    total_tokens = 0
    
    def aggregate_tokens(span_tree):
        """Recursively sum tokens from all spans"""
        nonlocal total_prompt_tokens, total_completion_tokens, total_tokens
        
        if span_tree.usage:
            total_prompt_tokens += span_tree.usage.get("prompt_tokens", 0)
            total_completion_tokens += span_tree.usage.get("completion_tokens", 0)
            total_tokens += span_tree.usage.get("total_tokens", 0)
        
        # Process children
        for child in span_tree.spans:
            aggregate_tokens(child)
    
    for span_tree in logged_spans:
        aggregate_tokens(span_tree)
    
    # Verify tokens are counted (from LLM calls)
    assert total_tokens > 0, "Expected non-zero total tokens from LLM calls"
    assert total_prompt_tokens > 0, "Expected non-zero prompt tokens"
    assert total_completion_tokens > 0, "Expected non-zero completion tokens"
    
    # Verify token consistency
    assert total_tokens == total_prompt_tokens + total_completion_tokens, (
        f"Token count mismatch: {total_tokens} != {total_prompt_tokens} + {total_completion_tokens}"
    )
    
    # The key verification: tokens should only come from LLM spans
    # This would fail before the fix if tool spans had duplicate token counts
    llm_only_total = 0
    
    def count_llm_tokens_only(span_tree):
        """Count tokens only from LLM spans"""
        nonlocal llm_only_total
        
        if span_tree.type == "llm" and span_tree.usage:
            llm_only_total += span_tree.usage.get("total_tokens", 0)
        
        # Process children
        for child in span_tree.spans:
            count_llm_tokens_only(child)
    
    for span_tree in logged_spans:
        count_llm_tokens_only(span_tree)
    
    # Aggregated tokens should equal LLM-only tokens (tool spans contribute nothing)
    assert total_tokens == llm_only_total, (
        f"Token duplication detected! Total: {total_tokens}, LLM-only: {llm_only_total}. "
        f"Difference suggests tool spans are incorrectly contributing tokens."
    )

