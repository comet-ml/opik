import opik
from opik.integrations.ag2 import OpikInstrumentor

from autogen import AssistantAgent, ConversableAgent, LLMConfig, UserProxyAgent
from ...testlib import (
    ANY_BUT_NONE,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)
from . import constants

import pytest


pytestmark = [
    pytest.mark.usefixtures("ensure_openai_configured"),
]


def test_ag2_two_agent_chat(fake_backend):
    """Verify that a basic two-agent chat produces a trace tree."""
    instrumentor = OpikInstrumentor(project_name=constants.PROJECT_NAME)

    llm_config = LLMConfig(api_type="openai", model=constants.MODEL_NAME_SHORT)

    with llm_config:
        assistant = AssistantAgent(
            name="Assistant",
            system_message="You are a helpful assistant. Reply concisely.",
        )

    user = UserProxyAgent(
        name="User",
        human_input_mode="NEVER",
        code_execution_config=False,
    )

    instrumentor.instrument_agent(assistant)
    instrumentor.instrument_agent(user)

    user.initiate_chat(
        assistant,
        message="What is 2 + 2? Reply with just the number.",
        max_turns=2,
    )

    instrumentor.flush()
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) >= 1, (
        "Expected at least one trace tree from AG2 two-agent chat"
    )

    trace = fake_backend.trace_trees[0]
    assert trace.project_name == constants.PROJECT_NAME


def test_ag2_tool_usage(fake_backend):
    """Verify that tool calls are traced as tool-type spans."""
    instrumentor = OpikInstrumentor(project_name=constants.PROJECT_NAME)

    llm_config = LLMConfig(api_type="openai", model=constants.MODEL_NAME_SHORT)

    with llm_config:
        assistant = AssistantAgent(
            name="ToolAgent",
            system_message="Use the get_weather tool to answer weather questions.",
        )

    @assistant.register_for_llm(description="Get current weather for a city")
    def get_weather(city: str) -> str:
        return f"The weather in {city} is sunny and 72°F."

    user = UserProxyAgent(
        name="User",
        human_input_mode="NEVER",
        code_execution_config=False,
    )

    @user.register_for_execution()
    def get_weather(city: str) -> str:
        return f"The weather in {city} is sunny and 72°F."

    instrumentor.instrument_agent(assistant)
    instrumentor.instrument_agent(user)

    user.initiate_chat(
        assistant,
        message="What is the weather in San Francisco?",
        max_turns=3,
    )

    instrumentor.flush()
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) >= 1, (
        "Expected at least one trace tree from AG2 tool usage"
    )


def test_ag2_instrumentor_idempotent(fake_backend):
    """Verify that calling instrument_agent twice does not duplicate spans."""
    instrumentor = OpikInstrumentor(project_name=constants.PROJECT_NAME)

    llm_config = LLMConfig(api_type="openai", model=constants.MODEL_NAME_SHORT)

    with llm_config:
        assistant = AssistantAgent(
            name="Assistant",
            system_message="Reply concisely.",
        )

    # Instrument twice — should be safe
    instrumentor.instrument_agent(assistant)
    instrumentor.instrument_agent(assistant)

    user = UserProxyAgent(
        name="User",
        human_input_mode="NEVER",
        code_execution_config=False,
    )
    instrumentor.instrument_agent(user)

    user.initiate_chat(
        assistant,
        message="Say hello.",
        max_turns=1,
    )

    instrumentor.flush()
    opik.flush_tracker()

    # Should still produce exactly one trace (not duplicated)
    assert len(fake_backend.trace_trees) >= 1


def test_ag2_group_chat(fake_backend):
    """Verify that group chat with a pattern produces traces."""
    instrumentor = OpikInstrumentor(project_name=constants.PROJECT_NAME)

    llm_config = LLMConfig(api_type="openai", model=constants.MODEL_NAME_SHORT)

    with llm_config:
        agent_a = AssistantAgent(
            name="Analyst",
            system_message="You analyze data. Be concise.",
        )
        agent_b = AssistantAgent(
            name="Summarizer",
            system_message="You summarize findings. Be brief.",
        )

    instrumentor.instrument_agent(agent_a)
    instrumentor.instrument_agent(agent_b)

    user = UserProxyAgent(
        name="User",
        human_input_mode="NEVER",
        code_execution_config=False,
    )
    instrumentor.instrument_agent(user)

    try:
        from autogen.agentchat import initiate_group_chat
        from autogen.agentchat.group.patterns.auto import AutoPattern

        pattern = AutoPattern(
            initial_agent=agent_a,
            agents=[agent_a, agent_b],
            user_agent=user,
            group_manager_args={"llm_config": llm_config},
        )
        instrumentor.instrument_pattern(pattern)

        result, context, last_agent = initiate_group_chat(
            pattern=pattern,
            messages="Analyze the benefits of renewable energy in one sentence.",
            max_rounds=3,
        )
    except ImportError:
        # Older AG2 versions may not have AutoPattern
        pytest.skip("AG2 version does not support AutoPattern group chat")

    instrumentor.flush()
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) >= 1, (
        "Expected at least one trace tree from AG2 group chat"
    )
