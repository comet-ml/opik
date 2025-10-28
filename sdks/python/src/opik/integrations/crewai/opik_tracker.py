import importlib.metadata
import logging
from typing import Optional

import crewai

import opik.semantic_version

from . import crewai_decorator, patchers

LOGGER = logging.getLogger(__name__)


def track_crewai(
    project_name: Optional[str] = None,
    crew: Optional[crewai.Crew] = None,
) -> None:
    """
    Tracks CrewAI activities by enabling tracking decorators for various critical methods.

    The function applies tracking decorators to key CrewAI components and methods, enabling logging
    or monitoring of activities. Tracking is enabled globally and can only be initialized once.

    If you use this tracker - please avoid using of OpenAI tracker to prevent duplicate
    logging of LLM calls and token usage.

    Parameters:
        project_name: The name of the project to associate with the tracking.
        crew: The Crew instance to track. Required for CrewAI v1.0.0+ to properly track LLM calls.
    """

    decorator_factory = crewai_decorator.CrewAITrackDecorator()

    crewai_wrapper = decorator_factory.track(
        project_name=project_name,
    )

    crewai.Crew.kickoff = crewai_wrapper(crewai.Crew.kickoff)
    crewai.Crew.kickoff_for_each = crewai_wrapper(crewai.Crew.kickoff_for_each)
    crewai.Agent.execute_task = crewai_wrapper(crewai.Agent.execute_task)
    crewai.Task.execute_sync = crewai_wrapper(crewai.Task.execute_sync)

    # Patch LiteLLM functions used by CrewAI
    patchers.patch_litellm_completion(project_name=project_name)

    # Patch Flow class (v1.0.0+)
    patchers.patch_flow(project_name=project_name)

    # Patch LLM clients used by CrewAI agents (v1.0.0+)
    if crew is not None and is_crewai_v1():
        patchers.patch_llm_client(crew, project_name)


def is_crewai_v1() -> bool:
    """
    Checks if CrewAI v1.0.0+ is installed.

    Returns:
        True if CrewAI v1.0.0+ is detected, False otherwise.
    """
    try:
        version_str = importlib.metadata.version("crewai")
        return opik.semantic_version.SemanticVersion.parse(version_str) >= "1.0.0"  # type: ignore
    except Exception:
        return False
