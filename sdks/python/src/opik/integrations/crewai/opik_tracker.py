from typing import Optional

import crewai

from . import crewai_decorator


def track_crewai(
    project_name: Optional[str] = None,
) -> None:
    decorator_factory = crewai_decorator.CrewAITrackDecorator()

    kickoff_decorator = decorator_factory.track(
        # type="llm",
        # name="chat_completion_create",
        project_name=project_name,
    )

    # todo
    # Crew.kickoff,
    # Crew.kickoff_for_each,
    # Crew.kickoff_async,
    # Crew.kickoff_for_each_async,
    # Agent.execute_task,
    # Task.execute_sync,

    crewai.Crew.kickoff = kickoff_decorator(crewai.Crew.kickoff)
    crewai.Agent.execute_task = kickoff_decorator(crewai.Agent.execute_task)

    return None
