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

    crewai_wrapper = decorator_factory.track(
        project_name=project_name,
    )

    crewai.Crew.kickoff = crewai_wrapper(crewai.Crew.kickoff)
    crewai.Crew.kickoff_for_each = crewai_wrapper(crewai.Crew.kickoff_for_each)
    crewai.Agent.execute_task = crewai_wrapper(crewai.Agent.execute_task)
    crewai.Task.execute_sync = crewai_wrapper(crewai.Task.execute_sync)
    litellm.completion = crewai_wrapper(litellm.completion)

    return None
