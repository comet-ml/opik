from typing import AsyncIterator, Iterator, List, Optional, Union

import google
import pytest
from google.adk import (
    agents as adk_agents,
    runners as adk_runners,
    sessions as adk_sessions,
    events as adk_events,
)

from opik import semantic_version
from opik.integrations.adk import OpikTracer
from .constants import APP_NAME, USER_ID, SESSION_ID, MODEL_NAME


pytest_skip_for_adk_older_than_1_3_0 = pytest.mark.skipif(
    semantic_version.SemanticVersion.parse(google.adk.__version__) < "1.3.0",
    reason="Test only applies to ADK versions >= 1.3.0",
)


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
    from the last event that actually carries content.

    ADK (1.31+) sometimes emits a trailing terminator event with content=None
    after the real final response, so we walk the list in reverse to find
    the last event whose content.parts is populated.
    """
    events = list(events_generator)
    if len(events) == 0:
        # As the error might occur in the background, we raise an exception here
        raise Exception("Agent failed to execute.")

    final_event = _pick_final_response_event(events)
    return final_event.content.parts[0].text


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
    from the last event that actually carries content. See the sync variant
    for the rationale on scanning in reverse.
    """
    collected_events = []
    async for event in events_generator:
        collected_events.append(event)

    if len(collected_events) == 0:
        # As the error might occur in the background, we raise an exception here
        raise Exception("Agent failed to execute.")

    final_event = _pick_final_response_event(collected_events)
    return final_event.content.parts[0].text


def _pick_final_response_event(
    events: List[adk_events.Event],
) -> adk_events.Event:
    """Return the last event that is a final agent response with text
    content. Skips trailing terminator events (content=None) that ADK
    1.31+ sometimes emits, and skips intermediate events such as
    pre-tool model messages or function_call/function_response parts —
    otherwise tool-failure tests (which never reach a final text
    response) would silently pick up a pre-tool chatter event and stop
    raising. Raises if no qualifying event is found."""
    for event in reversed(events):
        if not event.is_final_response():
            continue
        content = event.content
        if content is None or not content.parts:
            continue
        if content.parts[0].text is None:
            continue
        return event
    raise AssertionError(
        f"No final-response event with text content found among {len(events)} events. "
        f"Last event: {events[-1]!r}"
    )


def root_agent_sequential_with_translator_and_summarizer(
    opik_tracer: OpikTracer,
) -> adk_agents.SequentialAgent:
    translator_to_english = adk_agents.Agent(
        name="Translator",
        model=MODEL_NAME,
        description="Translates text to English.",
        instruction="Translate to English.",
        before_agent_callback=opik_tracer.before_agent_callback,
        after_agent_callback=opik_tracer.after_agent_callback,
        before_model_callback=opik_tracer.before_model_callback,
        after_model_callback=opik_tracer.after_model_callback,
    )

    summarizer = adk_agents.Agent(
        name="Summarizer",
        model=MODEL_NAME,
        description="Summarizes text to 1 sentence.",
        instruction="Summarize to one sentence.",
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
