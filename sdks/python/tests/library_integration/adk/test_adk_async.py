import opik
from typing import Optional, AsyncIterator
from . import agent_tools
from google.adk import agents as adk_agents
from google.adk import runners as adk_runners
from google.adk import sessions as adk_sessions
from google.adk import events as adk_events
from google.genai import types as genai_types

from opik.integrations.adk import OpikTracer
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_dict_has_keys,
    assert_equal,
)

import pytest
from opik.integrations.adk import helpers as opik_adk_helpers
from .constants import (
    APP_NAME,
    USER_ID,
    SESSION_ID,
    MODEL_NAME,
    EXPECTED_USAGE_KEYS_GOOGLE,
)


async def _async_build_runner(root_agent: adk_agents.Agent) -> adk_runners.Runner:
    session_service = adk_sessions.InMemorySessionService()
    _ = await session_service.create_session(
        app_name=APP_NAME, user_id=USER_ID, session_id=SESSION_ID
    )
    runner = adk_runners.Runner(
        agent=root_agent, app_name=APP_NAME, session_service=session_service
    )
    return runner


async def _async_extract_final_response_text(
    events_generator: AsyncIterator[adk_events.Event],
) -> Optional[str]:
    """
    Exhausts the async iterator of ADK events and returns the response text
    from the last event (presumably the final root agent response).
    """
    collected_events = []
    async for event in events_generator:
        collected_events.append(event)

    if len(collected_events) == 0:
        # As the error might occur in the background, we raise an exception here
        raise Exception("Agent failed to execute.")

    last_event: adk_events.Event = collected_events[-1]
    # Don't use only event.is_final_response() because it may be true for nested agents as well!
    assert (
        last_event.is_final_response()
        and last_event.content
        and last_event.content.parts
    )
    return last_event.content.parts[0].text


@pytest.mark.asyncio
async def test_adk__single_agent__multiple_tools__async_happyflow(fake_backend):
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
        tools=[agent_tools.get_weather],
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
        before_tool_callback=opik_tracer.before_tool_callback,
        after_tool_callback=opik_tracer.after_tool_callback,
    )

    runner = await _async_build_runner(root_agent)

    events_generator = runner.run_async(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user",
            parts=[genai_types.Part(text="What is the weather in New York?")],
        ),
    )
    final_response = await _async_extract_final_response_text(events_generator)

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
            "_opik_graph_definition": ANY_DICT,
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
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
    assert_dict_has_keys(trace_tree.spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)
    assert_dict_has_keys(trace_tree.spans[2].usage, EXPECTED_USAGE_KEYS_GOOGLE)


@pytest.mark.asyncio
async def test_adk__sequential_agent_with_subagents__every_subagent_has_its_own_span(
    fake_backend,
):
    opik_tracer = OpikTracer()

    translator_to_english = adk_agents.Agent(
        name="Translator",
        model=MODEL_NAME,
        description="Translates text to English.",
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
    )

    summarizer = adk_agents.Agent(
        name="Summarizer",
        model=MODEL_NAME,
        description="Summarizes text to 1 sentence.",
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
    )

    root_agent = adk_agents.SequentialAgent(
        name="TextProcessingAssistant",
        sub_agents=[translator_to_english, summarizer],
        description="Runs translator to english then summarizer, in order.",
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
    )

    runner = await _async_build_runner(root_agent)

    INPUT_GERMAN_TEXT = (
        "Wie große Sprachmodelle (LLMs) funktionieren\n\n"
        "Große Sprachmodelle (LLMs) werden mit riesigen Mengen an Text trainiert,\n"
        "um Muster in der Sprache zu erkennen. Sie verwenden eine Art neuronales Netzwerk,\n"
        "das Transformer genannt wird. Dieses ermöglicht es ihnen, den Kontext und die Beziehungen\n"
        "zwischen Wörtern zu verstehen.\n"
        "Wenn man einem LLM eine Eingabe gibt, sagt es die wahrscheinlichsten nächsten Wörter\n"
        "voraus – basierend auf allem, was es während des Trainings gelernt hat.\n"
        "Es „versteht“ nicht im menschlichen Sinne, aber es erzeugt Antworten, die oft intelligent wirken,\n"
        "weil es so viele Daten gesehen hat.\n"
        "Je mehr Daten und Training ein Modell hat, desto besser kann es Aufgaben wie das Beantworten von Fragen,\n"
        "das Schreiben von Texten oder das Zusammenfassen von Inhalten erfüllen.\n"
    )

    events_generator = runner.run_async(
        user_id=USER_ID,
        session_id=SESSION_ID,
        new_message=genai_types.Content(
            role="user", parts=[genai_types.Part(text=INPUT_GERMAN_TEXT)]
        ),
    )
    final_response = await _async_extract_final_response_text(events_generator)

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
            "_opik_graph_definition": ANY_DICT,
        },
        output=ANY_DICT.containing(
            {"content": {"parts": [{"text": final_response}], "role": "model"}}
        ),
        input={
            "role": "user",
            "parts": [{"text": INPUT_GERMAN_TEXT}],
        },
        thread_id=SESSION_ID,
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
                    )
                ],
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
    assert_dict_has_keys(trace_tree.spans[0].spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)
    assert_dict_has_keys(trace_tree.spans[1].spans[0].usage, EXPECTED_USAGE_KEYS_GOOGLE)
