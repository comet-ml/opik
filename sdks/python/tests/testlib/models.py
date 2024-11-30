from typing import List, Any, Optional, Dict

import dataclasses
import datetime

from .any_compare_helpers import ANY


@dataclasses.dataclass
class SpanModel:
    id: str
    start_time: datetime.datetime
    name: Optional[str] = None
    input: Any = None
    output: Any = None
    tags: Optional[List[str]] = None
    metadata: Optional[Dict[str, Any]] = None
    type: str = "general"
    usage: Optional[Dict[str, Any]] = None
    end_time: Optional[datetime.datetime] = None
    project_name: str = dataclasses.field(
        default_factory=lambda: ANY
    )  # we don't want to check the project name unless it's specified explicitly in the test
    spans: List["SpanModel"] = dataclasses.field(default_factory=list)
    feedback_scores: List["FeedbackScoreModel"] = dataclasses.field(
        default_factory=list
    )
    model: Optional[str] = None
    provider: Optional[str] = None


@dataclasses.dataclass
class TraceModel:
    id: str
    start_time: datetime.datetime
    name: Optional[str]
    project_name: str
    input: Any = None
    output: Any = None
    tags: Optional[List[str]] = None
    metadata: Optional[Dict[str, Any]] = None
    end_time: Optional[datetime.datetime] = None
    project_name: str = dataclasses.field(
        default_factory=lambda: ANY
    )  # we don't want to check the project name unless it's specified explicitly in the test
    spans: List["SpanModel"] = dataclasses.field(default_factory=list)
    feedback_scores: List["FeedbackScoreModel"] = dataclasses.field(
        default_factory=list
    )


@dataclasses.dataclass
class FeedbackScoreModel:
    id: str
    name: str
    value: float
    category_name: Optional[str] = None
    reason: Optional[str] = None
