"""Compatibility module for conversational coherence metric."""

from ..llm_judges.conversational_coherence.metric import (
    ConversationalCoherenceMetric,
)
from ..llm_judges.conversational_coherence import (  # noqa: F401
    schema as schema,
    templates as templates,
)

__all__ = ["ConversationalCoherenceMetric", "schema", "templates"]
