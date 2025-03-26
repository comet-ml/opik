from typing import Optional

import crewai

from . import crewai_decorator

__IS_TRACKING_ENABLED = False


def track_crewai(
    project_name: Optional[str] = None,
) -> None:
    """
    Tracks CrewAI activities by enabling tracking decorators for various critical methods.

    The function applies tracking decorators to key CrewAI components and methods, enabling logging
    or monitoring of activities. Tracking is enabled globally and can only be initialized once.

    If you use this tracker - please avoid using of OpenAI tracker to prevent duplicate
    logging of LLM calls and token usage.

    Parameters:
        project_name: The name of the project to associate with the tracking.
    """
    global __IS_TRACKING_ENABLED
    if __IS_TRACKING_ENABLED:
        return
    __IS_TRACKING_ENABLED = True

    decorator_factory = crewai_decorator.CrewAITrackDecorator()

    crewai_wrapper = decorator_factory.track(
        project_name=project_name,
    )

    import litellm

    crewai.Crew.kickoff = crewai_wrapper(crewai.Crew.kickoff)
    crewai.Crew.kickoff_for_each = crewai_wrapper(crewai.Crew.kickoff_for_each)
    crewai.Agent.execute_task = crewai_wrapper(crewai.Agent.execute_task)
    crewai.Task.execute_sync = crewai_wrapper(crewai.Task.execute_sync)
    litellm.completion = crewai_wrapper(litellm.completion)

    return None
