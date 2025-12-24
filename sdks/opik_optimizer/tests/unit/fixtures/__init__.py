"""
Test fixtures module for opik_optimizer.

This module contains reusable test helpers and mock builders that are
too complex for inline conftest.py definitions.

Submodules:
    - llm_mocks: Complex LLM response builders and scenarios
    - data_factories: Test data generation utilities
"""

from .llm_mocks import (
    LLMResponseBuilder,
    create_structured_response,
    create_chat_completion_response,
)
from .data_factories import (
    DatasetItemFactory,
    ChatPromptFactory,
    EvaluationResultFactory,
)

__all__ = [
    "LLMResponseBuilder",
    "create_structured_response",
    "create_chat_completion_response",
    "DatasetItemFactory",
    "ChatPromptFactory",
    "EvaluationResultFactory",
]
