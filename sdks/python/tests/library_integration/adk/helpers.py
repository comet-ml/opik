from typing import Iterator, Optional, Union, AsyncIterator

from google.adk import (
    agents as adk_agents,
    runners as adk_runners,
    sessions as adk_sessions,
    events as adk_events,
)

from opik.integrations.adk import OpikTracer
from .constants import APP_NAME, USER_ID, SESSION_ID, MODEL_NAME


def build_sync_runner(root_agent: adk_agents.Agent) -> adk_runners.Runner:
    session_service = adk_sessions.InMemorySessionService()
    _ = session_service.create_session_sync(
        app_name=APP_NAME, user_id=USER_ID, session_id=SESSION_ID
    )
    runner = adk_runners.Runner(
        agent=root_agent, app_name=APP_NAME, session_service=session_service
    )
    return runner


def extract_final_response_text(
    events_generator: Iterator[adk_events.Event],
) -> Optional[str]:
    """
    Exhausts the iterator of ADK events and returns the response text
    from the last event (presumably the final root agent response).
    """
    events_generator = list(events_generator)
    if len(events_generator) == 0:
        # As the error might occur in the background, we raise an exception here
        raise Exception("Agent failed to execute.")

    last_event: adk_events.Event = events_generator[-1]
    # Don't use only event.is_final_response() because it may be true for nested agents as well!
    assert (
        last_event.is_final_response()
        and last_event.content
        and last_event.content.parts
    )
    return last_event.content.parts[0].text


async def async_build_runner(
    root_agent: Union[adk_agents.Agent, adk_agents.SequentialAgent],
) -> adk_runners.Runner:
    session_service = adk_sessions.InMemorySessionService()
    _ = await session_service.create_session(
        app_name=APP_NAME, user_id=USER_ID, session_id=SESSION_ID
    )
    runner = adk_runners.Runner(
        agent=root_agent, app_name=APP_NAME, session_service=session_service
    )
    return runner


async def async_extract_final_response_text(
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


def root_agent_sequential_with_translator_and_summarizer(
    opik_tracer: OpikTracer,
) -> adk_agents.SequentialAgent:
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
    return root_agent
