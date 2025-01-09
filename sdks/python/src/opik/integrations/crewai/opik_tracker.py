from typing import Optional

import crewai
import litellm

from . import crewai_decorator

__IS_TRACKING_ENABLED = False


def track_crewai(
    project_name: Optional[str] = None,
) -> None:
    global __IS_TRACKING_ENABLED
    if __IS_TRACKING_ENABLED:
        return
    __IS_TRACKING_ENABLED = True

    decorator_factory = crewai_decorator.CrewAITrackDecorator()

    kickoff_decorator = decorator_factory.track(
        project_name=project_name,
    )

    crewai.Crew.kickoff = kickoff_decorator(crewai.Crew.kickoff)
    crewai.Crew.kickoff_for_each = kickoff_decorator(crewai.Crew.kickoff_for_each)
    crewai.Agent.execute_task = kickoff_decorator(crewai.Agent.execute_task)
    crewai.Task.execute_sync = kickoff_decorator(crewai.Task.execute_sync)
    litellm.completion = kickoff_decorator(litellm.completion)

    return None
