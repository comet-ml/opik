import enum
import sys
from typing import Literal, Optional

from typing_extensions import TypedDict

if sys.version_info < (3, 11):
    from typing_extensions import NotRequired, Required
else:
    from typing import NotRequired, Required

SpanType = Literal["general", "tool", "llm", "guardrail"]
FeedbackType = Literal["numerical", "categorical"]
CreatedByType = Literal["evaluation"]
AttachmentEntityType = Literal["trace", "span"]


class LLMProvider(str, enum.Enum):
    GOOGLE_VERTEXAI = "google_vertexai"
    """Used for gemini models hosted in VertexAI. https://cloud.google.com/vertex-ai"""

    GOOGLE_AI = "google_ai"
    """Used for gemini models hosted in GoogleAI. https://ai.google.dev/aistudio"""

    OPENAI = "openai"
    """Used for models hosted by OpenAI. https://platform.openai.com"""

    ANTHROPIC = "anthropic"
    """Used for models hosted by Anthropic. https://www.anthropic.com"""

    ANTHROPIC_VERTEXAI = "anthropic_vertexai"
    """Used for Anthropic models hosted by VertexAI. https://cloud.google.com/vertex-ai"""

    @classmethod
    def has_value(cls, value: str) -> bool:
        return value in [enum_item.value for enum_item in cls]


class DistributedTraceHeadersDict(TypedDict):
    """
    Contains headers for distributed tracing, returned by the :py:func:`opik.opik_context.get_distributed_trace_headers` function.
    """

    opik_trace_id: str
    opik_parent_span_id: str


class FeedbackScoreDict(TypedDict):
    """
    A TypedDict representing a feedback score.

    This class defines the structure for feedback scores, including required
    and optional fields such as the score's identifier, name, value, and
    an optional reason for the score.
    """

    id: NotRequired[str]
    """
    A unique identifier for the object this score should be assigned to.
    Refers to either the trace_id or span_id depending on how the score is logged.
    """

    name: Required[str]
    """The name of the feedback metric or criterion."""

    value: Required[float]
    """The numerical value of the feedback score."""

    category_name: NotRequired[Optional[str]]
    """An optional category name for the given score."""

    reason: NotRequired[Optional[str]]
    """An optional explanation or justification for the given score."""


class ErrorInfoDict(TypedDict):
    """
    A TypedDict representing the information about the error occurred.
    """

    exception_type: str
    """The name of the exception class"""

    message: NotRequired[str]
    """Exception message"""

    traceback: str
    """Exception traceback"""
