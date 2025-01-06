import functools
from typing import Optional

import crewai

from . import crewai_decorator


def track_crewai(
    project_name: Optional[str] = None,
) -> None:
    decorator_factory = crewai_decorator.CrewAITrackDecorator()

    kickoff_decorator = decorator_factory.track(
        project_name=project_name,
    )

    crewai.Crew.kickoff = kickoff_decorator(crewai.Crew.kickoff)
    crewai.Crew.kickoff_for_each = kickoff_decorator(crewai.Crew.kickoff_for_each)
    crewai.Crew.kickoff_async = kickoff_decorator(crewai.Crew.kickoff_async)
    crewai.Crew.kickoff_for_each_async = kickoff_decorator(crewai.Crew.kickoff_for_each_async)

    crewai.Agent.execute_task = kickoff_decorator(crewai.Agent.execute_task)
    crewai.Agent.create_agent_executor = create_agent_executor_wrapper(crewai.Agent.create_agent_executor)

    crewai.Task.execute_sync = kickoff_decorator(crewai.Task.execute_sync)
    crewai.Task.execute_async = kickoff_decorator(crewai.Task.execute_async)

    return None


def create_agent_executor_wrapper(method):
    @functools.wraps(method)
    def wrapped_method(*args, **kwargs):
        print("*** create_agent_executor_wrapper BEGIN ***")
        print(args[0].role.strip())
        # print(args)
        # print(kwargs)

        opik_obj = None

        if args[0].agent_executor and len(args[0].agent_executor.callbacks) > 1:
            for callback in args[0].agent_executor.callbacks:
                if isinstance(callback, crewai_decorator.OpikTokenCalcHandler):
                    opik_obj = callback
                    # todo reset token usage info?
                    break

        result = method(*args, **kwargs)

        if opik_obj is not None:
            args[0].agent_executor.callbacks = [opik_obj] + args[0].agent_executor.callbacks
            print("*** create_agent_executor_wrapper SET WRAPPER ***")

        print("*** create_agent_executor_wrapper END ***")
        return result

    return wrapped_method

