import pickle
from typing import Dict

import google.adk
import pydantic
import pytest
from google.adk import agents as adk_agents
from google.adk.agents import run_config
from google.adk.models import lite_llm as adk_lite_llm
from google.adk.tools import agent_tool as adk_agent_tool
from google.genai import types as genai_types

import opik
from opik import semantic_version
from opik.integrations.adk import OpikTracer, track_adk_agent_recursive
from opik.integrations.adk import helpers as opik_adk_helpers
from opik.integrations.adk import opik_tracer, legacy_opik_tracer
from . import agent_tools
from . import constants, helpers
from .constants import (
    APP_NAME,
    USER_ID,
    SESSION_ID,
    MODEL_NAME,
    EXPECTED_USAGE_KEYS_GOOGLE,
)
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_dict_has_keys,
    assert_equal,
)

# Maximum reasonable time-to-first-token in milliseconds for test assertions
MAX_REASONABLE_TTFT_MS = 60000


@pytest.mark.skipif(
    semantic_version.SemanticVersion.parse(google.adk.__version__) >= "1.3.0",
    reason="Test only applies to ADK versions < 1.3.0",
)
def test_adk__public_name_OpikTracer_is_legacy_implementation_for_old_adk_versions():
    """Test that OpikTracer maps to LegacyOpikTracer for ADK versions < 1.3.0"""
    assert OpikTracer is legacy_opik_tracer.LegacyOpikTracer


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_adk__public_name_OpikTracer_is_new_implementation_for_new_adk_versions():
    """Test that OpikTracer maps to OpikTracer for ADK versions >= 1.3.0"""
    assert OpikTracer is opik_tracer.OpikTracer


def test_adk__single_agent__single_tool__happyflow(fake_backend):
    opik_tracer = OpikTracer(
        project_name="adk-test",
        tags=["adk-test"],
        metadata={"adk-metadata-key": "adk-metadata-value"},
    )

    root_agent = adk_agents.Agent(
        name="weather_agent",
        model=MODEL_NAME,
        description=(
            "Agent to answer questions about the weather in a city (only 'New York' supported)."
        ),
        instruction=(
            "I can answer your questions about the weather in a city (only 'New York' supported)."
        ),
        tools=[agent_tools.get_weather],
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
        before_tool_callback=opik_tracer.before_tool_callback,
        after_tool_callback=opik_tracer.after_tool_callback,
    )

    runner = helpers.build_sync_runner(root_agent)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user",
            parts=[genai_types.Part(text="What is the weather in New York?")],
        ),
    )
    final_response = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="weather_agent",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata={
            "created_from": "google-adk",
            "adk-metadata-key": "adk-metadata-value",
            "adk_invocation_id": ANY_STRING,
            "app_name": APP_NAME,
            "user_id": USER_ID,
            "_opik_graph_definition": ANY_BUT_NONE,
        },
        tags=["adk-test"],
        output=ANY_DICT.containing(
            {"content": {"parts": [{"text": final_response}], "role": "model"}}
        ),
        input={
            "role": "user",
            "parts": [{"text": "What is the weather in New York?"}],
        },
        thread_id=SESSION_ID,
        project_name="adk-test",
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name=MODEL_NAME,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider=opik_adk_helpers.get_adk_provider(),
                model=MODEL_NAME,
                usage=ANY_DICT,
                project_name="adk-test",
                ttft=ANY_BUT_NONE,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="get_weather",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="tool",
                input={"city": "New York"},
                output={
                    "status": "success",
                    "report": "The weather in New York is sunny with a temperature of 25 degrees Celsius (41 degrees Fahrenheit).",
                },
                project_name="adk-test",
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name=MODEL_NAME,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider=opik_adk_helpers.get_adk_provider(),
                model=MODEL_NAME,
                usage=ANY_DICT,
                project_name="adk-test",
                ttft=ANY_BUT_NONE,
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
    assert_dict_has_keys(trace_tree.spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)
    assert_dict_has_keys(trace_tree.spans[2].usage, EXPECTED_USAGE_KEYS_GOOGLE)


def test_adk__single_agent__multiple_tools__two_invocations_lead_to_two_traces_with_the_same_thread_id(
    fake_backend,
):
    opik_tracer = OpikTracer()

    root_agent = adk_agents.Agent(
        name="weather_time_agent",
        model=MODEL_NAME,
        description=(
            "Agent to answer questions about the weather in a city (only 'New York' supported)."
        ),
        instruction=(
            "I can answer your questions about the weather in a city (only 'New York' supported)."
        ),
        tools=[
            agent_tools.get_weather,
            agent_tools.get_current_time,
        ],
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
        before_tool_callback=opik_tracer.before_tool_callback,
        after_tool_callback=opik_tracer.after_tool_callback,
    )

    runner = helpers.build_sync_runner(root_agent)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user",
            parts=[genai_types.Part(text="What is the weather in New York?")],
        ),
    )
    weather_question_response = helpers.extract_final_response_text(events_generator)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user", parts=[genai_types.Part(text="What is the time in New York?")]
        ),
    )
    time_question_response = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()

    EXPECTED_WEATHER_QUESTION_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="weather_time_agent",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata={
            "created_from": "google-adk",
            "adk_invocation_id": ANY_STRING,
            "app_name": APP_NAME,
            "user_id": USER_ID,
            "_opik_graph_definition": ANY_BUT_NONE,
        },
        output=ANY_DICT.containing(
            {
                "content": {
                    "parts": [{"text": weather_question_response}],
                    "role": "model",
                }
            }
        ),
        input={
            "role": "user",
            "parts": [{"text": "What is the weather in New York?"}],
        },
        thread_id=SESSION_ID,
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name=MODEL_NAME,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider=opik_adk_helpers.get_adk_provider(),
                model=MODEL_NAME,
                usage=ANY_DICT,
                ttft=ANY_BUT_NONE,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="get_weather",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="tool",
                input={"city": "New York"},
                output={
                    "status": "success",
                    "report": "The weather in New York is sunny with a temperature of 25 degrees Celsius (41 degrees Fahrenheit).",
                },
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name=MODEL_NAME,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider=opik_adk_helpers.get_adk_provider(),
                model=MODEL_NAME,
                usage=ANY_DICT,
                ttft=ANY_BUT_NONE,
            ),
        ],
    )

    EXPECTED_TIME_QUESTION_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="weather_time_agent",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata={
            "created_from": "google-adk",
            "adk_invocation_id": ANY_STRING,
            "app_name": APP_NAME,
            "user_id": USER_ID,
            "_opik_graph_definition": ANY_BUT_NONE,
        },
        output=ANY_DICT.containing(
            {"content": {"parts": [{"text": time_question_response}], "role": "model"}}
        ),
        input={
            "role": "user",
            "parts": [{"text": "What is the time in New York?"}],
        },
        thread_id=SESSION_ID,
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name=MODEL_NAME,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider=opik_adk_helpers.get_adk_provider(),
                model=MODEL_NAME,
                usage=ANY_DICT,
                ttft=ANY_BUT_NONE,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="get_current_time",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="tool",
                input={"city": "New York"},
                output={
                    "status": "success",
                    "report": ANY_STRING.starting_with(
                        "The current time in New York is"
                    ),
                },
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name=MODEL_NAME,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider=opik_adk_helpers.get_adk_provider(),
                model=MODEL_NAME,
                usage=ANY_DICT,
                ttft=ANY_BUT_NONE,
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 2
    weather_trace_tree = fake_backend.trace_trees[0]
    time_trace_tree = fake_backend.trace_trees[1]

    assert_equal(EXPECTED_WEATHER_QUESTION_TRACE_TREE, weather_trace_tree)
    assert_dict_has_keys(weather_trace_tree.spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)
    assert_dict_has_keys(weather_trace_tree.spans[2].usage, EXPECTED_USAGE_KEYS_GOOGLE)

    assert_equal(EXPECTED_TIME_QUESTION_TRACE_TREE, time_trace_tree)
    assert_dict_has_keys(time_trace_tree.spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)
    assert_dict_has_keys(time_trace_tree.spans[2].usage, EXPECTED_USAGE_KEYS_GOOGLE)


def test_adk__sequential_agent_with_subagents__every_subagent_has_its_own_span(
    fake_backend,
):
    opik_tracer = OpikTracer()
    root_agent = helpers.root_agent_sequential_with_translator_and_summarizer(
        opik_tracer
    )
    runner = helpers.build_sync_runner(root_agent)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user", parts=[genai_types.Part(text=constants.INPUT_GERMAN_TEXT)]
        ),
    )
    final_response = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()
    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="TextProcessingAssistant",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata={
            "created_from": "google-adk",
            "adk_invocation_id": ANY_STRING,
            "app_name": APP_NAME,
            "user_id": USER_ID,
            "_opik_graph_definition": ANY_BUT_NONE,
        },
        output=ANY_DICT.containing(
            {"content": {"parts": [{"text": final_response}], "role": "model"}}
        ),
        input={
            "role": "user",
            "parts": [{"text": constants.INPUT_GERMAN_TEXT}],
        },
        thread_id=SESSION_ID,
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="Translator",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="general",
                input=ANY_DICT,
                output=ANY_DICT,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name=MODEL_NAME,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        last_updated_at=ANY_BUT_NONE,
                        metadata=ANY_DICT,
                        type="llm",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        provider=opik_adk_helpers.get_adk_provider(),
                        model=MODEL_NAME,
                        usage=ANY_DICT,
                        ttft=ANY_BUT_NONE,
                    )
                ],
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="Summarizer",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="general",
                input=ANY_DICT,
                output=ANY_DICT,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name=MODEL_NAME,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        last_updated_at=ANY_BUT_NONE,
                        metadata=ANY_DICT,
                        type="llm",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        provider=opik_adk_helpers.get_adk_provider(),
                        model=MODEL_NAME,
                        usage=ANY_DICT,
                        ttft=ANY_BUT_NONE,
                    )
                ],
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
    assert_dict_has_keys(trace_tree.spans[0].spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)
    assert_dict_has_keys(trace_tree.spans[1].spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)


def test_adk__tool_calls_tracked_function__tracked_function_span_attached_to_the_tool_span(
    fake_backend,
):
    opik_tracer = OpikTracer(
        tags=["adk-test"], metadata={"adk-metadata-key": "adk-metadata-value"}
    )

    @opik.track(type="tool")
    def is_city_supported(city: str) -> bool:
        return city.lower() == "new york"

    def get_weather(city: str) -> Dict[str, str]:
        if not is_city_supported(city):
            return {
                "status": "error",
                "error_message": f"Weather information for '{city}' is not available.",
            }

        return {
            "status": "success",
            "report": f"The weather in {city} is sunny with a temperature of 25 degrees Celsius (41 degrees Fahrenheit).",
        }

    root_agent = adk_agents.Agent(
        name="weather_time_agent",
        model=MODEL_NAME,
        description=(
            "Agent to answer questions about the weather in a city (only 'New York' supported)."
        ),
        instruction=(
            "I can answer your questions about the weather in a city (only 'New York' supported)."
        ),
        tools=[get_weather],
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
        before_tool_callback=opik_tracer.before_tool_callback,
        after_tool_callback=opik_tracer.after_tool_callback,
    )

    runner = helpers.build_sync_runner(root_agent)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user",
            parts=[genai_types.Part(text="What is the weather in New York?")],
        ),
    )
    final_response = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="weather_time_agent",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata={
            "created_from": "google-adk",
            "adk-metadata-key": "adk-metadata-value",
            "adk_invocation_id": ANY_STRING,
            "app_name": APP_NAME,
            "user_id": USER_ID,
            "_opik_graph_definition": ANY_BUT_NONE,
        },
        tags=["adk-test"],
        output=ANY_DICT.containing(
            {"content": {"parts": [{"text": final_response}], "role": "model"}}
        ),
        input={
            "role": "user",
            "parts": [{"text": "What is the weather in New York?"}],
        },
        thread_id=SESSION_ID,
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name=MODEL_NAME,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider=opik_adk_helpers.get_adk_provider(),
                model=MODEL_NAME,
                usage=ANY_DICT,
                ttft=ANY_BUT_NONE,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="get_weather",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="tool",
                input={"city": "New York"},
                output={
                    "status": "success",
                    "report": "The weather in New York is sunny with a temperature of 25 degrees Celsius (41 degrees Fahrenheit).",
                },
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="is_city_supported",
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        last_updated_at=ANY_BUT_NONE,
                        type="tool",
                        input={"city": "New York"},
                        output={"output": True},
                    )
                ],
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name=MODEL_NAME,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider=opik_adk_helpers.get_adk_provider(),
                model=MODEL_NAME,
                usage=ANY_DICT,
                ttft=ANY_BUT_NONE,
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
    assert_dict_has_keys(trace_tree.spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)
    assert_dict_has_keys(trace_tree.spans[2].usage, EXPECTED_USAGE_KEYS_GOOGLE)


def test_adk__litellm_used_for_openai_model__usage_logged_in_openai_format(
    fake_backend,
):
    model_name = "openai/gpt-5-nano"

    opik_tracer = OpikTracer(
        tags=["adk-test"], metadata={"adk-metadata-key": "adk-metadata-value"}
    )

    root_agent = adk_agents.Agent(
        name="weather_time_agent",
        model=adk_lite_llm.LiteLlm(model_name),
        description=(
            "Agent to answer questions about the weather in a city (only 'New York' supported)."
        ),
        instruction=(
            "I can answer your questions about the weather in a city (only 'New York' supported)."
        ),
        tools=[agent_tools.get_weather],
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
        before_tool_callback=opik_tracer.before_tool_callback,
        after_tool_callback=opik_tracer.after_tool_callback,
    )

    runner = helpers.build_sync_runner(root_agent)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user",
            parts=[genai_types.Part(text="What is the weather in New York?")],
        ),
    )
    final_response = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="weather_time_agent",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata={
            "created_from": "google-adk",
            "adk-metadata-key": "adk-metadata-value",
            "adk_invocation_id": ANY_STRING,
            "app_name": APP_NAME,
            "user_id": USER_ID,
            "_opik_graph_definition": ANY_BUT_NONE,
        },
        tags=["adk-test"],
        output=ANY_DICT.containing(
            {"content": {"parts": [{"text": final_response}], "role": "model"}}
        ),
        input={
            "role": "user",
            "parts": [{"text": "What is the weather in New York?"}],
        },
        thread_id=SESSION_ID,
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name=ANY_STRING.containing(model_name.split("/")[-1]),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider="openai",  # not necessary supported by opik, just taken from the prefix of litellm model
                model=ANY_STRING.starting_with(model_name.split("/")[-1]),
                usage=ANY_DICT,
                ttft=ANY_BUT_NONE,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="get_weather",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="tool",
                input={"city": "New York"},
                output={
                    "status": "success",
                    "report": "The weather in New York is sunny with a temperature of 25 degrees Celsius (41 degrees Fahrenheit).",
                },
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name=ANY_STRING.containing(model_name.split("/")[-1]),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider="openai",  # not necessary supported by opik, just taken from the prefix of litellm model
                model=ANY_STRING.starting_with(model_name.split("/")[-1]),
                usage=ANY_DICT,
                ttft=ANY_BUT_NONE,
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
    EXPECTED_USAGE_KEYS_IN_OPENAI_FORMAT = [
        "prompt_tokens",
        "completion_tokens",
        "total_tokens",
        "original_usage.prompt_tokens",
        "original_usage.completion_tokens",
        "original_usage.total_tokens",
    ]
    assert_dict_has_keys(
        trace_tree.spans[0].usage, EXPECTED_USAGE_KEYS_IN_OPENAI_FORMAT
    )
    assert_dict_has_keys(
        trace_tree.spans[2].usage, EXPECTED_USAGE_KEYS_IN_OPENAI_FORMAT
    )


def test_adk__litellm_used_for_openai_model__streaming_mode_is_SSE__usage_logged_in_openai_format(
    fake_backend,
):
    model_name = "openai/gpt-5-nano"

    opik_tracer = OpikTracer(
        tags=["adk-test"], metadata={"adk-metadata-key": "adk-metadata-value"}
    )

    root_agent = adk_agents.Agent(
        name="weather_time_agent",
        model=adk_lite_llm.LiteLlm(model_name),
        description=(
            "Agent to answer questions about the weather in a city (only 'New York' supported)."
        ),
        instruction=(
            "I can answer your questions about the weather in a city (only 'New York' supported)."
        ),
        tools=[agent_tools.get_weather],
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
        before_tool_callback=opik_tracer.before_tool_callback,
        after_tool_callback=opik_tracer.after_tool_callback,
    )

    runner = helpers.build_sync_runner(root_agent)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        run_config=run_config.RunConfig(streaming_mode=run_config.StreamingMode.SSE),
        new_message=genai_types.Content(
            role="user",
            parts=[genai_types.Part(text="What is the weather in New York?")],
        ),
    )
    final_response = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="weather_time_agent",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata={
            "created_from": "google-adk",
            "adk-metadata-key": "adk-metadata-value",
            "adk_invocation_id": ANY_STRING,
            "app_name": APP_NAME,
            "user_id": USER_ID,
            "_opik_graph_definition": ANY_BUT_NONE,
        },
        tags=["adk-test"],
        output=ANY_DICT.containing(
            {"content": {"parts": [{"text": final_response}], "role": "model"}}
        ),
        input={
            "role": "user",
            "parts": [{"text": "What is the weather in New York?"}],
        },
        thread_id=SESSION_ID,
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name=ANY_STRING.containing(model_name.split("/")[-1]),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider="openai",  # not necessary supported by opik, just taken from the prefix of litellm model
                model=ANY_STRING.starting_with(model_name.split("/")[-1]),
                usage=ANY_DICT,
                ttft=ANY_BUT_NONE,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="get_weather",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="tool",
                input={"city": "New York"},
                output={
                    "status": "success",
                    "report": "The weather in New York is sunny with a temperature of 25 degrees Celsius (41 degrees Fahrenheit).",
                },
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name=ANY_STRING.containing(model_name.split("/")[-1]),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider="openai",  # not necessary supported by opik, just taken from the prefix of litellm model
                model=ANY_STRING.starting_with(model_name.split("/")[-1]),
                usage=ANY_DICT,
                ttft=ANY_BUT_NONE,
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
    EXPECTED_USAGE_KEYS_IN_OPENAI_FORMAT = [
        "prompt_tokens",
        "completion_tokens",
        "total_tokens",
        # TODO: add back when ADK will support it. For now ADK converts LiteLLM usage to Google format
        # "original_usage.prompt_tokens",
        # "original_usage.completion_tokens",
        # "original_usage.total_tokens",
    ]
    assert_dict_has_keys(
        trace_tree.spans[0].usage, EXPECTED_USAGE_KEYS_IN_OPENAI_FORMAT
    )
    assert_dict_has_keys(
        trace_tree.spans[2].usage, EXPECTED_USAGE_KEYS_IN_OPENAI_FORMAT
    )


def test_adk__track_adk_agent_recursive__sequential_agent_with_subagent__every_subagent_is_tracked(
    fake_backend,
):
    opik_tracer = OpikTracer()

    translator_to_english = adk_agents.Agent(
        name="Translator",
        model=MODEL_NAME,
        description="Translates text to English.",
    )
    summarizer = adk_agents.Agent(
        name="Summarizer",
        model=MODEL_NAME,
        description="Summarizes text to 1 sentence.",
    )
    root_agent = adk_agents.SequentialAgent(
        name="TextProcessingAssistant",
        sub_agents=[translator_to_english, summarizer],
        description="Runs translator to english then summarizer, in order.",
    )

    track_adk_agent_recursive(root_agent, opik_tracer)

    runner = helpers.build_sync_runner(root_agent)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user", parts=[genai_types.Part(text=constants.INPUT_GERMAN_TEXT)]
        ),
    )
    final_response = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()
    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="TextProcessingAssistant",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata={
            "created_from": "google-adk",
            "adk_invocation_id": ANY_STRING,
            "app_name": APP_NAME,
            "user_id": USER_ID,
            "_opik_graph_definition": ANY_BUT_NONE,
        },
        output=ANY_DICT.containing(
            {"content": {"parts": [{"text": final_response}], "role": "model"}}
        ),
        input={
            "role": "user",
            "parts": [{"text": constants.INPUT_GERMAN_TEXT}],
        },
        thread_id=SESSION_ID,
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="Translator",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="general",
                input=ANY_DICT,
                output=ANY_DICT,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name=MODEL_NAME,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        last_updated_at=ANY_BUT_NONE,
                        metadata=ANY_DICT,
                        type="llm",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        provider=opik_adk_helpers.get_adk_provider(),
                        model=MODEL_NAME,
                        usage=ANY_DICT,
                        ttft=ANY_BUT_NONE,
                    )
                ],
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="Summarizer",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="general",
                input=ANY_DICT,
                output=ANY_DICT,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name=MODEL_NAME,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        last_updated_at=ANY_BUT_NONE,
                        metadata=ANY_DICT,
                        type="llm",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        provider=opik_adk_helpers.get_adk_provider(),
                        model=MODEL_NAME,
                        usage=ANY_DICT,
                        ttft=ANY_BUT_NONE,
                    )
                ],
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
    assert_dict_has_keys(trace_tree.spans[0].spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)
    assert_dict_has_keys(trace_tree.spans[1].spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_adk__track_adk_agent_recursive__agent_tool_is_used__agent_tool_is_tracked(
    fake_backend,
):
    opik_tracer = OpikTracer()

    translator_to_english = adk_agents.Agent(
        name="Translator",
        model=MODEL_NAME,
        description="Translates text to English.",
    )

    root_agent = adk_agents.Agent(
        name="TextProcessingAssistant",
        model=MODEL_NAME,
        tools=[adk_agent_tool.AgentTool(agent=translator_to_english)],
        description="Agent responsible for translating text to english by invoking a special tool for that.",
    )

    track_adk_agent_recursive(root_agent, opik_tracer)

    runner = helpers.build_sync_runner(root_agent)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user", parts=[genai_types.Part(text=constants.INPUT_GERMAN_TEXT)]
        ),
    )
    final_response = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()
    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="TextProcessingAssistant",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata={
            "created_from": "google-adk",
            "adk_invocation_id": ANY_STRING,
            "app_name": APP_NAME,
            "user_id": USER_ID,
            "_opik_graph_definition": ANY_BUT_NONE,
        },
        output=ANY_DICT.containing(
            {"content": {"parts": [{"text": final_response}], "role": "model"}}
        ),
        input={
            "role": "user",
            "parts": [{"text": constants.INPUT_GERMAN_TEXT}],
        },
        thread_id=SESSION_ID,
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name=MODEL_NAME,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider=opik_adk_helpers.get_adk_provider(),
                model=MODEL_NAME,
                usage=ANY_DICT,
                ttft=ANY_BUT_NONE,
            ),
            SpanModel(  # from tool callback
                id=ANY_BUT_NONE,
                name="Translator",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="tool",
                input=ANY_DICT,
                output=ANY_DICT,
                spans=[
                    SpanModel(  # from agent callback
                        id=ANY_BUT_NONE,
                        name="Translator",
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        last_updated_at=ANY_BUT_NONE,
                        metadata=ANY_DICT,
                        type="general",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        spans=[
                            SpanModel(  # from model callback inside the agent tool
                                id=ANY_BUT_NONE,
                                name=MODEL_NAME,
                                start_time=ANY_BUT_NONE,
                                end_time=ANY_BUT_NONE,
                                last_updated_at=ANY_BUT_NONE,
                                metadata=ANY_DICT,
                                type="llm",
                                input=ANY_DICT,
                                output=ANY_DICT,
                                provider=opik_adk_helpers.get_adk_provider(),
                                model=MODEL_NAME,
                                usage=ANY_DICT,
                                ttft=ANY_BUT_NONE,
                            )
                        ],
                    )
                ],
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name=MODEL_NAME,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider=opik_adk_helpers.get_adk_provider(),
                model=MODEL_NAME,
                usage=ANY_DICT,
                ttft=ANY_BUT_NONE,
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    assert_dict_has_keys(trace_tree.spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)
    assert_dict_has_keys(
        trace_tree.spans[1].spans[0].spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE
    )
    assert_dict_has_keys(trace_tree.spans[2].usage, EXPECTED_USAGE_KEYS_GOOGLE)


def test_adk__track_adk_agent_recursive__idempotent_calls_make_no_duplicated_callbacks():
    opik_tracer = OpikTracer()

    translator_to_english = adk_agents.Agent(
        name="Translator",
        model=MODEL_NAME,
        description="Translates text to English.",
    )

    root_agent = adk_agents.Agent(
        name="TextProcessingAssistant",
        model=MODEL_NAME,
        tools=[adk_agent_tool.AgentTool(agent=translator_to_english)],
        description="Agent responsible for translating text to english by invoking a special tool for that.",
    )

    track_adk_agent_recursive(root_agent, opik_tracer)

    first_translator_after_agent_callback = translator_to_english.after_agent_callback
    first_translator_before_agent_callback = translator_to_english.before_agent_callback
    first_translator_after_tool_callback = translator_to_english.after_tool_callback
    first_translator_before_tool_callback = translator_to_english.before_tool_callback
    first_translator_after_model_callback = translator_to_english.after_model_callback
    first_translator_before_model_callback = translator_to_english.before_model_callback

    first_root_after_agent_callback = root_agent.after_agent_callback
    first_root_before_agent_callback = root_agent.before_agent_callback
    first_root_after_tool_callback = root_agent.after_tool_callback
    first_root_before_tool_callback = root_agent.before_tool_callback
    first_root_after_model_callback = root_agent.after_model_callback
    first_root_before_model_callback = root_agent.before_model_callback

    track_adk_agent_recursive(root_agent, opik_tracer)

    assert (
        translator_to_english.after_agent_callback
        is first_translator_after_agent_callback
    )
    assert (
        translator_to_english.before_agent_callback
        is first_translator_before_agent_callback
    )
    assert (
        translator_to_english.after_tool_callback
        is first_translator_after_tool_callback
    )
    assert (
        translator_to_english.before_tool_callback
        is first_translator_before_tool_callback
    )
    assert (
        translator_to_english.after_model_callback
        is first_translator_after_model_callback
    )
    assert (
        translator_to_english.before_model_callback
        is first_translator_before_model_callback
    )

    assert root_agent.after_agent_callback is first_root_after_agent_callback
    assert root_agent.before_agent_callback is first_root_before_agent_callback
    assert root_agent.after_tool_callback is first_root_after_tool_callback
    assert root_agent.before_tool_callback is first_root_before_tool_callback
    assert root_agent.after_model_callback is first_root_after_model_callback
    assert root_agent.before_model_callback is first_root_before_model_callback


def test_adk__opik_tracer__unpickled_object_works_as_expected(fake_backend):
    opik_tracer = OpikTracer(
        project_name="adk-test",
        tags=["adk-test"],
        metadata={"adk-metadata-key": "adk-metadata-value"},
    )

    pickled_opik_tracer = pickle.dumps(opik_tracer)
    opik_tracer = pickle.loads(pickled_opik_tracer)

    root_agent = adk_agents.Agent(
        name="weather_time_agent",
        model=MODEL_NAME,
        description=(
            "Agent to answer questions about the weather in a city (only 'New York' supported)."
        ),
        instruction=(
            "I can answer your questions about the weather in a city (only 'New York' supported)."
        ),
        tools=[agent_tools.get_weather],
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
        before_tool_callback=opik_tracer.before_tool_callback,
        after_tool_callback=opik_tracer.after_tool_callback,
    )

    runner = helpers.build_sync_runner(root_agent)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user",
            parts=[genai_types.Part(text="What is the weather in New York?")],
        ),
    )
    final_response = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="weather_time_agent",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata={
            "created_from": "google-adk",
            "adk-metadata-key": "adk-metadata-value",
            "adk_invocation_id": ANY_STRING,
            "app_name": APP_NAME,
            "user_id": USER_ID,
            "_opik_graph_definition": ANY_BUT_NONE,
        },
        tags=["adk-test"],
        output=ANY_DICT.containing(
            {"content": {"parts": [{"text": final_response}], "role": "model"}}
        ),
        input={
            "role": "user",
            "parts": [{"text": "What is the weather in New York?"}],
        },
        thread_id=SESSION_ID,
        project_name="adk-test",
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name=MODEL_NAME,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider=opik_adk_helpers.get_adk_provider(),
                model=MODEL_NAME,
                usage=ANY_DICT,
                project_name="adk-test",
                ttft=ANY_BUT_NONE,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="get_weather",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="tool",
                input={"city": "New York"},
                output={
                    "status": "success",
                    "report": "The weather in New York is sunny with a temperature of 25 degrees Celsius (41 degrees Fahrenheit).",
                },
                project_name="adk-test",
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name=MODEL_NAME,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider=opik_adk_helpers.get_adk_provider(),
                model=MODEL_NAME,
                usage=ANY_DICT,
                project_name="adk-test",
                ttft=ANY_BUT_NONE,
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
    assert_dict_has_keys(trace_tree.spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)
    assert_dict_has_keys(trace_tree.spans[2].usage, EXPECTED_USAGE_KEYS_GOOGLE)


def test_adk__agent_with_response_schema__happyflow(
    fake_backend,
):
    opik_tracer = OpikTracer()

    class SummaryResult(pydantic.BaseModel):
        summary: str

    summarizer = adk_agents.Agent(
        name="Summarizer",
        model=MODEL_NAME,
        description="Summarizes text to 1 sentence.",
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
        output_schema=SummaryResult,
    )

    runner = helpers.build_sync_runner(summarizer)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user", parts=[genai_types.Part(text=constants.INPUT_GERMAN_TEXT)]
        ),
    )
    final_response = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()
    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="Summarizer",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata={
            "created_from": "google-adk",
            "adk_invocation_id": ANY_STRING,
            "app_name": APP_NAME,
            "user_id": USER_ID,
            "_opik_graph_definition": ANY_BUT_NONE,
        },
        output=ANY_DICT.containing(
            {"content": {"parts": [{"text": final_response}], "role": "model"}}
        ),
        input={
            "role": "user",
            "parts": [{"text": constants.INPUT_GERMAN_TEXT}],
        },
        thread_id=SESSION_ID,
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name=MODEL_NAME,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider=opik_adk_helpers.get_adk_provider(),
                model=MODEL_NAME,
                usage=ANY_DICT,
                ttft=ANY_BUT_NONE,
            )
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
    assert_dict_has_keys(trace_tree.spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_adk__llm_call_failed__error_info_is_logged_in_llm_span(fake_backend):
    opik_tracer = OpikTracer(
        project_name="adk-test",
        tags=["adk-test"],
        metadata={"adk-metadata-key": "adk-metadata-value"},
    )

    root_agent = adk_agents.Agent(
        name="weather_agent",
        model=adk_lite_llm.LiteLlm("openai/invalid-model-name"),
        description=(
            "Agent to answer questions about the weather in a city (only 'New York' supported)."
        ),
        instruction=(
            "I can answer your questions about the weather in a city (only 'New York' supported)."
        ),
        tools=[agent_tools.get_weather],
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
        before_tool_callback=opik_tracer.before_tool_callback,
        after_tool_callback=opik_tracer.after_tool_callback,
    )

    runner = helpers.build_sync_runner(root_agent)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user",
            parts=[genai_types.Part(text="What is the weather in New York?")],
        ),
    )
    with pytest.raises(Exception):
        # `events_generator` generator will not produce a single event and finish immediately
        # because first llm call fails.
        # `_extract_final_response_text` will raise an exception because it is
        # programmed to do so when there are no events (we still have to try to exhaust the generator though,
        # because it is necessary for agent to actuallyexecute)
        _ = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="weather_agent",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata={
            "created_from": "google-adk",
            "adk-metadata-key": "adk-metadata-value",
            "adk_invocation_id": ANY_STRING,
            "app_name": APP_NAME,
            "user_id": USER_ID,
            "_opik_graph_definition": ANY_BUT_NONE,
        },
        tags=["adk-test"],
        output=None,
        input={
            "role": "user",
            "parts": [{"text": "What is the weather in New York?"}],
        },
        thread_id=SESSION_ID,
        project_name="adk-test",
        error_info={
            "exception_type": ANY_STRING,
            "message": ANY_STRING,
            "traceback": ANY_STRING,
        },
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="invalid-model-name",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=None,
                model="invalid-model-name",
                usage=None,
                provider="openai",
                project_name="adk-test",
                error_info={
                    "exception_type": ANY_STRING,
                    "message": ANY_STRING,
                    "traceback": ANY_STRING,
                },
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_adk__tool_call_failed__error_info_is_logged_in_tool_span(fake_backend):
    opik_tracer = OpikTracer(
        project_name="adk-test",
        tags=["adk-test"],
        metadata={"adk-metadata-key": "adk-metadata-value"},
    )

    def get_weather(city: str) -> str:
        1 / 0
        return ""

    root_agent = adk_agents.Agent(
        name="weather_agent",
        model=MODEL_NAME,
        description=(
            "Agent to answer questions about the weather in a city (only 'New York' supported)."
        ),
        instruction=(
            "I can answer your questions about the weather in a city (only 'New York' supported)."
        ),
        tools=[get_weather],
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
        before_tool_callback=opik_tracer.before_tool_callback,
        after_tool_callback=opik_tracer.after_tool_callback,
    )

    runner = helpers.build_sync_runner(root_agent)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user",
            parts=[genai_types.Part(text="What is the weather in New York?")],
        ),
    )
    with pytest.raises(Exception):
        # `events_generator` generator will not produce a single event and finish immediately
        # because first llm call fails.
        # `_extract_final_response_text` will raise an exception because it is
        # programmed to do so when there are no events (we still have to try to exhaust the generator though,
        # because it is necessary for agent to actuallyexecute)
        _ = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="weather_agent",
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        metadata={
            "created_from": "google-adk",
            "adk-metadata-key": "adk-metadata-value",
            "adk_invocation_id": ANY_STRING,
            "app_name": APP_NAME,
            "user_id": USER_ID,
            "_opik_graph_definition": ANY_BUT_NONE,
        },
        tags=["adk-test"],
        output=None,
        input={
            "role": "user",
            "parts": [{"text": "What is the weather in New York?"}],
        },
        thread_id=SESSION_ID,
        project_name="adk-test",
        error_info={
            "exception_type": "ZeroDivisionError",
            "message": ANY_STRING,
            "traceback": ANY_STRING,
        },
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name=MODEL_NAME,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="llm",
                input=ANY_DICT,
                output=ANY_DICT,
                provider=opik_adk_helpers.get_adk_provider(),
                model=MODEL_NAME,
                usage=ANY_DICT,
                project_name="adk-test",
                ttft=ANY_BUT_NONE,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="get_weather",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                metadata=ANY_DICT,
                type="tool",
                input={"city": "New York"},
                output=None,
                error_info={
                    "exception_type": "ZeroDivisionError",
                    "message": ANY_STRING,
                    "traceback": ANY_STRING,
                },
                project_name="adk-test",
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


@pytest.mark.skipif(
    semantic_version.SemanticVersion.parse(google.adk.__version__) < "1.3.0",
    reason="Test only applies to ADK versions > 1.3.0",
)
def test_adk__transfer_to_agent__tracked_and_span_created(
    fake_backend,
):
    project_name = "adk_transfer_to_agent_test"
    opik_tracer = OpikTracer(project_name=project_name)

    translator_to_english = adk_agents.Agent(
        name="Translator",
        model=MODEL_NAME,
        description="Translates text to English.",
        instruction="Translate text to English.",
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
    )

    root_agent = adk_agents.Agent(
        name="Text_Assistant",
        model=MODEL_NAME,
        instruction="Translate text to English.",
        sub_agents=[translator_to_english],
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
    )

    runner = helpers.build_sync_runner(root_agent)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user", parts=[genai_types.Part(text=constants.INPUT_GERMAN_TEXT)]
        ),
    )
    _ = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()

    provider = opik_adk_helpers.get_adk_provider()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="Text_Assistant",
        project_name=project_name,
        input=ANY_DICT,
        output=ANY_DICT,
        metadata=ANY_DICT,
        end_time=ANY_BUT_NONE,
        ttft=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name=MODEL_NAME,
                input=ANY_DICT,
                output=ANY_DICT,
                metadata=ANY_DICT,
                type="llm",
                usage=ANY_DICT,
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                model=MODEL_NAME,
                provider=provider,
                last_updated_at=ANY_BUT_NONE,
                ttft=ANY_BUT_NONE,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="execute_tool transfer_to_agent",
                type="general",
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                last_updated_at=ANY_BUT_NONE,
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="Translator",
                input={
                    "parts": [{"text": constants.INPUT_GERMAN_TEXT}],
                    "role": "user",
                },
                output=ANY_DICT,
                metadata=ANY_DICT,
                type="general",
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name=MODEL_NAME,
                        input=ANY_DICT,
                        output=ANY_DICT,
                        metadata=ANY_DICT,
                        type="llm",
                        usage=ANY_DICT,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        model=MODEL_NAME,
                        provider=provider,
                        last_updated_at=ANY_BUT_NONE,
                        ttft=ANY_BUT_NONE,
                    )
                ],
                last_updated_at=ANY_BUT_NONE,
            ),
        ],
        thread_id=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
    )

    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=trace_tree)
    assert_dict_has_keys(trace_tree.spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)
    assert_dict_has_keys(trace_tree.spans[2].spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)


@pytest.fixture
def disable_tracing():
    opik.set_tracing_active(False)
    yield
    opik.set_tracing_active(True)


def test_adk__tracing_disabled__no_spans_created(fake_backend, disable_tracing):
    opik_tracer = OpikTracer(
        project_name="adk-test",
        tags=["adk-test"],
        metadata={"adk-metadata-key": "adk-metadata-value"},
    )

    root_agent = adk_agents.Agent(
        name="weather_agent",
        model=MODEL_NAME,
        description=(
            "Agent to answer questions about the weather in a city (only 'New York' supported)."
        ),
        instruction=(
            "I can answer your questions about the weather in a city (only 'New York' supported)."
        ),
        tools=[agent_tools.get_weather],
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
        before_tool_callback=opik_tracer.before_tool_callback,
        after_tool_callback=opik_tracer.after_tool_callback,
    )

    runner = helpers.build_sync_runner(root_agent)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user",
            parts=[genai_types.Part(text="What is the weather in New York?")],
        ),
    )
    _ = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 0
    assert len(fake_backend.span_trees) == 0


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_adk__llm_call__time_to_first_token_tracked_in_span_ttft_field(fake_backend):
    """Test that time-to-first-token is tracked and stored in LLM span's ttft field."""
    opik_tracer = OpikTracer(
        project_name="adk-test",
        tags=["adk-test"],
        metadata={"adk-metadata-key": "adk-metadata-value"},
    )

    root_agent = adk_agents.Agent(
        name="weather_agent",
        model=MODEL_NAME,
        description=(
            "Agent to answer questions about the weather in a city (only 'New York' supported)."
        ),
        instruction=(
            "I can answer your questions about the weather in a city (only 'New York' supported)."
        ),
        tools=[agent_tools.get_weather],
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
        before_tool_callback=opik_tracer.before_tool_callback,
        after_tool_callback=opik_tracer.after_tool_callback,
    )

    runner = helpers.build_sync_runner(root_agent)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user",
            parts=[genai_types.Part(text="What is the weather in New York?")],
        ),
    )
    _ = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    llm_spans = [span for span in trace_tree.spans if span.type == "llm"]
    assert len(llm_spans) > 0, "Expected at least one LLM span"

    for llm_span in llm_spans:
        assert llm_span.ttft is not None, "LLM span should have ttft field set"
        assert isinstance(llm_span.ttft, (int, float)), (
            f"ttft should be a number, got {type(llm_span.ttft)}"
        )
        assert llm_span.ttft >= 0, f"ttft should be non-negative, got {llm_span.ttft}"
        assert llm_span.ttft < MAX_REASONABLE_TTFT_MS, (
            f"ttft should be reasonable (< {MAX_REASONABLE_TTFT_MS}ms), got {llm_span.ttft}"
        )

    # Verify trace-level TTFT (time from trace start to first LLM token)
    assert trace_tree.ttft is not None, "Trace should have ttft field set"
    assert isinstance(trace_tree.ttft, (int, float)), (
        f"Trace ttft should be a number, got {type(trace_tree.ttft)}"
    )
    assert trace_tree.ttft >= 0, (
        f"Trace ttft should be non-negative, got {trace_tree.ttft}"
    )
    assert trace_tree.ttft < MAX_REASONABLE_TTFT_MS, (
        f"Trace ttft should be reasonable (< {MAX_REASONABLE_TTFT_MS}ms), got {trace_tree.ttft}"
    )


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_adk__llm_call__time_to_first_token_tracked_for_streaming_responses(
    fake_backend,
):
    """Test that time-to-first-token is tracked correctly for streaming responses."""
    opik_tracer = OpikTracer(
        project_name="adk-test",
        tags=["adk-test"],
        metadata={"adk-metadata-key": "adk-metadata-value"},
    )

    root_agent = adk_agents.Agent(
        name="weather_agent",
        model=MODEL_NAME,
        description=(
            "Agent to answer questions about the weather in a city (only 'New York' supported)."
        ),
        instruction=(
            "I can answer your questions about the weather in a city (only 'New York' supported)."
        ),
        tools=[agent_tools.get_weather],
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
        before_tool_callback=opik_tracer.before_tool_callback,
        after_tool_callback=opik_tracer.after_tool_callback,
    )

    runner = helpers.build_sync_runner(root_agent)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        run_config=run_config.RunConfig(streaming_mode=run_config.StreamingMode.SSE),
        new_message=genai_types.Content(
            role="user",
            parts=[genai_types.Part(text="What is the weather in New York?")],
        ),
    )
    _ = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    llm_spans = [span for span in trace_tree.spans if span.type == "llm"]
    assert len(llm_spans) > 0, "Expected at least one LLM span"

    for llm_span in llm_spans:
        assert llm_span.ttft is not None, (
            "LLM span should have ttft field set for streaming responses"
        )
        assert isinstance(llm_span.ttft, (int, float)), (
            f"ttft should be a number, got {type(llm_span.ttft)}"
        )
        assert llm_span.ttft >= 0, f"ttft should be non-negative, got {llm_span.ttft}"
        assert llm_span.ttft < MAX_REASONABLE_TTFT_MS, (
            f"ttft should be reasonable (< {MAX_REASONABLE_TTFT_MS}ms), got {llm_span.ttft}"
        )

    # Verify trace-level TTFT for streaming responses
    assert trace_tree.ttft is not None, (
        "Trace should have ttft field set for streaming responses"
    )
    assert isinstance(trace_tree.ttft, (int, float)), (
        f"Trace ttft should be a number, got {type(trace_tree.ttft)}"
    )
    assert trace_tree.ttft >= 0, (
        f"Trace ttft should be non-negative, got {trace_tree.ttft}"
    )
    assert trace_tree.ttft < MAX_REASONABLE_TTFT_MS, (
        f"Trace ttft should be reasonable (< {MAX_REASONABLE_TTFT_MS}ms), got {trace_tree.ttft}"
    )


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_adk__llm_call__time_to_first_token_tracked_for_multiple_llm_calls(
    fake_backend,
):
    """Test that time-to-first-token is tracked separately for each LLM call."""
    opik_tracer = OpikTracer(
        project_name="adk-test",
        tags=["adk-test"],
        metadata={"adk-metadata-key": "adk-metadata-value"},
    )

    root_agent = adk_agents.Agent(
        name="weather_time_agent",
        model=MODEL_NAME,
        description=(
            "Agent to answer questions about the weather in a city (only 'New York' supported)."
        ),
        instruction=(
            "I can answer your questions about the weather in a city (only 'New York' supported)."
        ),
        tools=[agent_tools.get_weather, agent_tools.get_current_time],
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
        before_tool_callback=opik_tracer.before_tool_callback,
        after_tool_callback=opik_tracer.after_tool_callback,
    )

    runner = helpers.build_sync_runner(root_agent)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user",
            parts=[genai_types.Part(text="What is the weather in New York?")],
        ),
    )
    _ = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    llm_spans = [span for span in trace_tree.spans if span.type == "llm"]
    assert len(llm_spans) >= 2, (
        "Expected at least two LLM spans (one before tool, one after)"
    )

    for llm_span in llm_spans:
        assert llm_span.ttft is not None, "All LLM spans should have ttft field set"
        assert isinstance(llm_span.ttft, (int, float)), (
            f"ttft should be a number, got {type(llm_span.ttft)}"
        )
        assert llm_span.ttft >= 0, f"ttft should be non-negative, got {llm_span.ttft}"
        assert llm_span.ttft < MAX_REASONABLE_TTFT_MS, (
            f"ttft should be reasonable (< {MAX_REASONABLE_TTFT_MS}ms), got {llm_span.ttft}"
        )

    ttft_values = [span.ttft for span in llm_spans]
    assert len(set(ttft_values)) >= 2, (
        "Expected at least two distinct TTFT values for multiple LLM calls"
    )

    # Verify trace-level TTFT (set once from the first LLM call)
    assert trace_tree.ttft is not None, "Trace should have ttft field set"
    assert isinstance(trace_tree.ttft, (int, float)), (
        f"Trace ttft should be a number, got {type(trace_tree.ttft)}"
    )
    assert trace_tree.ttft >= 0, (
        f"Trace ttft should be non-negative, got {trace_tree.ttft}"
    )
    assert trace_tree.ttft < MAX_REASONABLE_TTFT_MS, (
        f"Trace ttft should be reasonable (< {MAX_REASONABLE_TTFT_MS}ms), got {trace_tree.ttft}"
    )


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_adk__llm_call__time_to_first_token_not_present_when_no_content(fake_backend):
    """Test that time-to-first-token is not tracked when response has no content."""
    opik_tracer = OpikTracer(
        project_name="adk-test",
        tags=["adk-test"],
        metadata={"adk-metadata-key": "adk-metadata-value"},
    )

    root_agent = adk_agents.Agent(
        name="weather_agent",
        model=MODEL_NAME,
        description=(
            "Agent to answer questions about the weather in a city (only 'New York' supported)."
        ),
        instruction=(
            "I can answer your questions about the weather in a city (only 'New York' supported)."
        ),
        tools=[agent_tools.get_weather],
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
        before_tool_callback=opik_tracer.before_tool_callback,
        after_tool_callback=opik_tracer.after_tool_callback,
    )

    runner = helpers.build_sync_runner(root_agent)

    # Use a simple query that should generate a response
    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user",
            parts=[genai_types.Part(text="Hello")],
        ),
    )
    _ = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    llm_spans = [span for span in trace_tree.spans if span.type == "llm"]
    assert len(llm_spans) > 0, "Expected at least one LLM span"

    any_span_has_ttft = False
    for llm_span in llm_spans:
        if llm_span.output is not None and llm_span.usage is not None:
            if llm_span.ttft is not None:
                any_span_has_ttft = True
                assert isinstance(llm_span.ttft, (int, float)), (
                    f"ttft should be a number, got {type(llm_span.ttft)}"
                )
                assert llm_span.ttft >= 0, (
                    f"ttft should be non-negative, got {llm_span.ttft}"
                )
        else:
            assert llm_span.ttft is None, (
                f"LLM span without content should not have ttft set. "
                f"Span output: {llm_span.output}, usage: {llm_span.usage}, ttft: {llm_span.ttft}"
            )

    # Trace TTFT should be set if any LLM span had content (first token detected)
    if any_span_has_ttft:
        assert trace_tree.ttft is not None, (
            "Trace should have ttft if any LLM span has ttft"
        )
        assert isinstance(trace_tree.ttft, (int, float)), (
            f"Trace ttft should be a number, got {type(trace_tree.ttft)}"
        )
        assert trace_tree.ttft >= 0, (
            f"Trace ttft should be non-negative, got {trace_tree.ttft}"
        )


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_adk__llm_call__time_to_first_token_tracked_for_sequential_agents(fake_backend):
    """Test that time-to-first-token is tracked for each LLM call in sequential agents."""
    opik_tracer = OpikTracer()

    root_agent = helpers.root_agent_sequential_with_translator_and_summarizer(
        opik_tracer
    )

    runner = helpers.build_sync_runner(root_agent)

    events_generator = runner.run(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user", parts=[genai_types.Part(text=constants.INPUT_GERMAN_TEXT)]
        ),
    )
    _ = helpers.extract_final_response_text(events_generator)

    opik.flush_tracker()
    assert len(fake_backend.trace_trees) > 0
    trace_tree = fake_backend.trace_trees[0]

    # Check that all LLM spans in nested agents have time_to_first_token
    def collect_llm_spans(span):
        """Recursively collect all LLM spans."""
        llm_spans = []
        if span.type == "llm":
            llm_spans.append(span)
        if hasattr(span, "spans") and span.spans:
            for child_span in span.spans:
                llm_spans.extend(collect_llm_spans(child_span))
        return llm_spans

    all_llm_spans = []
    for span in trace_tree.spans:
        all_llm_spans.extend(collect_llm_spans(span))

    assert len(all_llm_spans) >= 2, (
        "Expected at least two LLM spans (one per sub-agent)"
    )

    for llm_span in all_llm_spans:
        assert llm_span.ttft is not None, (
            "All LLM spans in sequential agents should have ttft field set"
        )
        assert isinstance(llm_span.ttft, (int, float)), (
            f"ttft should be a number, got {type(llm_span.ttft)}"
        )
        assert llm_span.ttft >= 0, f"ttft should be non-negative, got {llm_span.ttft}"
        assert llm_span.ttft < MAX_REASONABLE_TTFT_MS, (
            f"ttft should be reasonable (< {MAX_REASONABLE_TTFT_MS}ms), got {llm_span.ttft}"
        )

    # Verify trace-level TTFT for sequential agents
    assert trace_tree.ttft is not None, (
        "Trace should have ttft field set for sequential agents"
    )
    assert isinstance(trace_tree.ttft, (int, float)), (
        f"Trace ttft should be a number, got {type(trace_tree.ttft)}"
    )
    assert trace_tree.ttft >= 0, (
        f"Trace ttft should be non-negative, got {trace_tree.ttft}"
    )
    assert trace_tree.ttft < MAX_REASONABLE_TTFT_MS, (
        f"Trace ttft should be reasonable (< {MAX_REASONABLE_TTFT_MS}ms), got {trace_tree.ttft}"
    )
