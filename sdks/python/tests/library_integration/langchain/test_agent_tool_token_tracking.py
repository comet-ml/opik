"""
Test for token tracking in agent workflows with tool calls.

This test verifies that tool calls do not incorrectly contribute to token usage,
which was causing token duplication in agentic trajectories.

See: https://github.com/comet-ml/opik/issues/4574
"""

import pytest
from langchain_core.prompts import PromptTemplate
from langchain_core.runnables import RunnableLambda

from opik.integrations.langchain import OpikTracer
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)


def simulated_tool_call(input_text: str) -> str:
    """Simulates a tool call that doesn't use LLM."""
    return f"Tool result for: {input_text}"


@pytest.mark.parametrize("use_streaming", [False, True])
def test_langchain_tool_calls_do_not_have_token_usage(
    fake_backend,
    ensure_openai_configured,
    use_streaming,
):
    """
    Test that tool/function calls in a LangChain workflow do not have usage data,
    preventing token duplication when backend aggregates across spans.
    
    This test:
    1. Creates a chain with LLM call followed by a tool (lambda) call
    2. Executes the chain
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

    # Create a simple chain: LLM -> Tool
    # The tool is represented as a RunnableLambda which will be tracked as type="tool"
    prompt = PromptTemplate.from_template("Say hello to {name}")
    tool_func = RunnableLambda(simulated_tool_call)
    
    # Chain: prompt -> llm -> tool
    chain = prompt | llm | tool_func

    # Execute chain with tracer
    opik_tracer = OpikTracer(tags=["test-tool-tokens"], project_name="test-tool-tokens")
    
    result = chain.invoke(
        {"name": "Alice"},
        config={"callbacks": [opik_tracer]}
    )
    
    opik_tracer.flush()
    
    # Get all logged spans
    logged_spans = fake_backend.span_trees

    # Verify we have spans
    assert len(logged_spans) > 0, "Expected at least one span to be logged"
    
    # Find LLM and tool/general spans
    llm_spans = []
    non_llm_spans = []
    
    def collect_spans(span_tree):
        """Recursively collect all spans from the tree"""
        if span_tree.type == "llm":
            llm_spans.append(span_tree)
        else:
            non_llm_spans.append(span_tree)
        
        # Process children
        for child in span_tree.spans:
            collect_spans(child)
    
    for span_tree in logged_spans:
        collect_spans(span_tree)
    
    # Verify we have both LLM and non-LLM spans
    assert len(llm_spans) > 0, "Expected at least one LLM span"
    assert len(non_llm_spans) > 0, "Expected at least one non-LLM span (tool/chain)"
    
    # Critical assertion: LLM spans should have usage data
    for llm_span in llm_spans:
        assert llm_span.usage is not None, f"LLM span {llm_span.name} should have usage data"
        assert "prompt_tokens" in llm_span.usage, f"LLM span {llm_span.name} should have prompt_tokens"
        assert "completion_tokens" in llm_span.usage, f"LLM span {llm_span.name} should have completion_tokens"
        assert "total_tokens" in llm_span.usage, f"LLM span {llm_span.name} should have total_tokens"
        assert llm_span.usage["total_tokens"] > 0, f"LLM span {llm_span.name} should have non-zero tokens"
    
    # Critical assertion: Non-LLM spans (tool/chain) should NOT have usage data
    for non_llm_span in non_llm_spans:
        # Non-LLM spans should either have no usage field or empty usage
        if non_llm_span.usage is not None:
            # If usage exists, it should be empty or have all zero values
            assert (
                not non_llm_span.usage 
                or all(v == 0 for v in non_llm_span.usage.values() if isinstance(v, (int, float)))
            ), f"Non-LLM span {non_llm_span.name} (type={non_llm_span.type}) should not have token usage data, but got: {non_llm_span.usage}"


@pytest.mark.parametrize("use_streaming", [False, True])
def test_token_aggregation_excludes_non_llm_spans(
    fake_backend,
    ensure_openai_configured,
    use_streaming,
):
    """
    Test that total token count aggregation excludes non-LLM spans.
    
    This simulates what the backend does when it sums up usage across spans.
    We verify that non-LLM spans don't contribute to the total.
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

    # Create a simple chain with LLM and tool
    prompt = PromptTemplate.from_template("Say hello to {name}")
    tool_func = RunnableLambda(simulated_tool_call)
    chain = prompt | llm | tool_func

    # Execute chain with tracer
    opik_tracer = OpikTracer(tags=["test-aggregation"], project_name="test-token-aggregation")
    
    chain.invoke(
        {"name": "Bob"},
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
    # This would fail before the fix if non-LLM spans had duplicate token counts
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
    
    # Aggregated tokens should equal LLM-only tokens (non-LLM spans contribute nothing)
    assert total_tokens == llm_only_total, (
        f"Token duplication detected! Total: {total_tokens}, LLM-only: {llm_only_total}. "
        f"Difference suggests non-LLM spans are incorrectly contributing tokens."
    )

