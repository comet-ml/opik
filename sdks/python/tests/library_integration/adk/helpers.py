from typing import Iterator, Optional

from google.adk import (
    agents as adk_agents,
    runners as adk_runners,
    sessions as adk_sessions,
    events as adk_events,
)

from .constants import APP_NAME, USER_ID, SESSION_ID


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
