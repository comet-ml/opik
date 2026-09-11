"""
Patcher for LiteLLM completion functions used by CrewAI.

This module patches litellm.completion and litellm.acompletion with Opik tracking.
CrewAI v0.x uses LiteLLM internally for LLM calls.
"""

import logging
from typing import Optional

import litellm

import opik.integrations.litellm

LOGGER = logging.getLogger(__name__)


def patch_litellm_completion(project_name: Optional[str] = None) -> None:
    """
    Patches LiteLLM completion functions used by CrewAI.

    Args:
        project_name: The name of the project to associate with tracking.
    """
    litellm.completion = opik.integrations.litellm.track_completion(
        project_name=project_name
    )(litellm.completion)
    litellm.acompletion = opik.integrations.litellm.track_completion(
        project_name=project_name
    )(litellm.acompletion)
