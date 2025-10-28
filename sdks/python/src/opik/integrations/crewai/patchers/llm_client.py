"""
Client patchers for CrewAI LLM providers (v1.0.0+).

This module handles patching of LLM clients used by CrewAI agents with Opik tracking.
Each provider has its own patching function that handles missing dependencies gracefully.
"""

import logging
from typing import Any, Optional, TYPE_CHECKING

import crewai

if TYPE_CHECKING:
    import crewai.llms.providers.openai.completion as openai_completion
    import crewai.llms.providers.anthropic.completion as anthropic_completion
    import crewai.llms.providers.gemini.completion as gemini_completion
    import crewai.llms.providers.bedrock.completion as bedrock_completion

LOGGER = logging.getLogger(__name__)


def patch_llm_client(crew: crewai.Crew, project_name: Optional[str]) -> None:
    """
    Patches LLM clients used by CrewAI agents with Opik tracking.

    Handles missing provider libraries gracefully by logging warnings instead of failing.

    Args:
        crew: The Crew instance containing agents to patch.
        project_name: The name of the project to associate with tracking.
    """
    for agent in crew.agents:
        _patch_single_llm_client(agent.llm, project_name)


def _patch_single_llm_client(llm: Any, project_name: Optional[str]) -> None:
    """
    Patches an LLM client based on its provider type.

    Args:
        llm: The CrewAI LLM instance to patch.
        project_name: The name of the project to associate with tracking.
    """
    if _is_openai_llm(llm):
        _patch_openai_client(llm, project_name)
    elif _is_anthropic_llm(llm):
        _patch_anthropic_client(llm, project_name)
    elif _is_gemini_llm(llm):
        _patch_gemini_client(llm, project_name)
    elif _is_bedrock_llm(llm):
        _patch_bedrock_client(llm, project_name)


def _is_openai_llm(llm: Any) -> bool:
    """
    Checks if LLM is an OpenAI provider.

    Args:
        llm: The CrewAI LLM to check.

    Returns:
        True if LLM is OpenAI provider, False otherwise.
    """
    try:
        import crewai.llms.providers.openai.completion

        return isinstance(llm, crewai.llms.providers.openai.completion.OpenAICompletion)
    except ImportError:
        return False


def _is_anthropic_llm(llm: Any) -> bool:
    """
    Checks if LLM is an Anthropic provider.

    Args:
        llm: The CrewAI LLM to check.

    Returns:
        True if LLM is Anthropic provider, False otherwise.
    """
    try:
        import crewai.llms.providers.anthropic.completion

        return isinstance(
            llm, crewai.llms.providers.anthropic.completion.AnthropicCompletion
        )
    except ImportError:
        return False


def _is_gemini_llm(llm: Any) -> bool:
    """
    Checks if LLM is a Gemini provider.

    Args:
        llm: The CrewAI LLM to check.

    Returns:
        True if LLM is Gemini provider, False otherwise.
    """
    try:
        import crewai.llms.providers.gemini.completion

        return isinstance(llm, crewai.llms.providers.gemini.completion.GeminiCompletion)
    except ImportError:
        return False


def _is_bedrock_llm(llm: Any) -> bool:
    """
    Checks if LLM is a Bedrock provider.

    Args:
        llm: The CrewAI LLM to check.

    Returns:
        True if LLM is Bedrock provider, False otherwise.
    """
    try:
        import crewai.llms.providers.bedrock.completion

        return isinstance(
            llm, crewai.llms.providers.bedrock.completion.BedrockCompletion
        )
    except ImportError:
        return False


def _patch_openai_client(
    llm: "openai_completion.OpenAICompletion", project_name: Optional[str]
) -> None:
    """
    Patches OpenAI client for the given LLM.

    Args:
        llm: The CrewAI LLM instance with OpenAI client to patch.
        project_name: The name of the project to associate with tracking.
    """
    try:
        import opik.integrations.openai

        llm.client = opik.integrations.openai.track_openai(
            llm.client, project_name=project_name
        )
    except Exception:
        LOGGER.warning("Failed to track OpenAI client for LLM", exc_info=True)


def _patch_anthropic_client(
    llm: "anthropic_completion.AnthropicCompletion", project_name: Optional[str]
) -> None:
    """
    Patches Anthropic client for the given LLM.

    Args:
        llm: The CrewAI LLM instance with Anthropic client to patch.
        project_name: The name of the project to associate with tracking.
    """
    try:
        import opik.integrations.anthropic

        llm.client = opik.integrations.anthropic.track_anthropic(
            llm.client, project_name=project_name
        )
    except Exception:
        LOGGER.warning("Failed to track Anthropic client for LLM", exc_info=True)


def _patch_gemini_client(
    llm: "gemini_completion.GeminiCompletion", project_name: Optional[str]
) -> None:
    """
    Patches Gemini client for the given LLM.

    Args:
        llm: The CrewAI LLM instance with Gemini client to patch.
        project_name: The name of the project to associate with tracking.
    """
    try:
        import opik.integrations.genai

        llm.client = opik.integrations.genai.track_genai(
            llm.client, project_name=project_name
        )
    except Exception:
        LOGGER.warning("Failed to track Gemini client for LLM", exc_info=True)


def _patch_bedrock_client(
    llm: "bedrock_completion.BedrockCompletion", project_name: Optional[str]
) -> None:
    """
    Patches Bedrock client for the given LLM.

    Args:
        llm: The CrewAI LLM instance with Bedrock client to patch.
        project_name: The name of the project to associate with tracking.
    """
    try:
        import opik.integrations.bedrock

        llm.client = opik.integrations.bedrock.track_bedrock(
            llm.client, project_name=project_name
        )
    except Exception:
        LOGGER.warning("Failed to track Bedrock client for LLM", exc_info=True)
