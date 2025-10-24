from typing import Optional

from crewai import Crew
from crewai import Agent
from crewai import Task

import litellm

from opik.integrations.litellm import track_completion

from . import crewai_decorator, flow_patchers

__IS_TRACKING_ENABLED = False


def track_crewai(
    project_name: Optional[str] = None,
    crew: Optional[Crew] = None,
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

    Crew.kickoff = crewai_wrapper(Crew.kickoff)
    Crew.kickoff_for_each = crewai_wrapper(Crew.kickoff_for_each)
    Agent.execute_task = crewai_wrapper(Agent.execute_task)
    Task.execute_sync = crewai_wrapper(Task.execute_sync)

    # Patch LiteLLM functions used by CrewAI
    _patch_litellm_completion(project_name=project_name)

    flow_patchers.patch_flow_init(project_name=project_name)
    flow_patchers.patch_flow_kickoff_async(project_name=project_name)

    if crew is not None:
        import crewai.llms.providers.openai.completion
        import crewai.llms.providers.anthropic.completion
        import crewai.llms.providers.gemini.completion
        import crewai.llms.providers.bedrock.completion

        # patch LLM clients used by CrewAI (openai, anthropic, gemini, bedrock) using
        # existing Opik integration
        # Note: only azure ai is not supported yet.
        for agent in crew.agents:
            if isinstance(agent.llm, crewai.llms.providers.openai.completion.OpenAICompletion):
                from opik.integrations.openai import track_openai
                agent.llm.client = track_openai(agent.llm.client, project_name=project_name)
            elif isinstance(agent.llm, crewai.llms.providers.anthropic.completion.AnthropicCompletion):
                from opik.integrations.anthropic import track_anthropic
                agent.llm.client = track_anthropic(agent.llm.client, project_name=project_name)
            elif isinstance(agent.llm, crewai.llms.providers.gemini.completion.GeminiCompletion):
                from opik.integrations.genai import track_genai
                agent.llm.client = track_genai(agent.llm.client, project_name=project_name)
            elif isinstance(agent.llm, crewai.llms.providers.bedrock.completion.BedrockCompletion):
                from opik.integrations.bedrock import track_bedrock
                agent.llm.client = track_bedrock(agent.llm.client, project_name=project_name)


    return None


def _patch_litellm_completion(project_name: Optional[str] = None) -> None:
    litellm.completion = track_completion(project_name=project_name)(litellm.completion)
    litellm.acompletion = track_completion(project_name=project_name)(
        litellm.acompletion
    )
