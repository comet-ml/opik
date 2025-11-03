"""Compatibility module for session completeness metric."""

from ..llm_judges.session_completeness.metric import SessionCompletenessQuality
from ..llm_judges.session_completeness import schema as schema  # noqa: F401
from ..llm_judges.session_completeness import templates as templates  # noqa: F401

__all__ = ["SessionCompletenessQuality", "schema", "templates"]
