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
from .assertions import (
    assert_baseline_early_stop,
    assert_invalid_prompt_raises,
)
from .prompt_builders import (
    make_baseline_prompt,
    make_two_prompt_bundle,
)
from .message_builders import (
    Message,
    system_message,
    user_message,
    assistant_message,
    tool_message,
    role_only,
)

__all__ = [
    "LLMResponseBuilder",
    "create_structured_response",
    "create_chat_completion_response",
    "DatasetItemFactory",
    "ChatPromptFactory",
    "EvaluationResultFactory",
    "assert_baseline_early_stop",
    "assert_invalid_prompt_raises",
    "make_baseline_prompt",
    "make_two_prompt_bundle",
    "Message",
    "system_message",
    "user_message",
    "assistant_message",
    "tool_message",
    "role_only",
]
