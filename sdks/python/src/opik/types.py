import sys

from typing import Literal, Optional
from typing_extensions import TypedDict

if sys.version_info < (3, 11):
    from typing_extensions import Required, NotRequired
else:
    from typing import Required, NotRequired

SpanType = Literal["general", "tool", "llm"]
FeedbackType = Literal["numerical", "categorical"]
CreatedByType = Literal["evaluation"]


class UsageDict(TypedDict):
    """
    A TypedDict representing token usage information.

    This class defines the structure for token usage, including fields
    for completion tokens, prompt tokens, and the total number of tokens used.
    """

    completion_tokens: int
    """The number of tokens used for the completion."""

    prompt_tokens: int
    """The number of tokens used for the prompt."""

    total_tokens: int
    """The total number of tokens used, including both prompt and completion."""


class DistributedTraceHeadersDict(TypedDict):
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
