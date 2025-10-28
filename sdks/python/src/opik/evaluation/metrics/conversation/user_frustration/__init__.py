"""Compatibility module for user frustration metric."""

from ...llm_judges.conversation.user_frustration.metric import (
    UserFrustrationMetric,
)
from ...llm_judges.conversation.user_frustration import schema as schema  # noqa: F401
from ...llm_judges.conversation.user_frustration import templates as templates  # noqa: F401

__all__ = ["UserFrustrationMetric", "schema", "templates"]
