from typing import List, Any, Optional, Dict

from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.types import ErrorInfoDict

import dataclasses
import datetime


@dataclasses.dataclass
class FeedbackScoreModel:
    """
    Represents a model for a feedback score used to evaluate specific spans or traces.

    This class stores and manages feedback scores linked to defined criteria, including
    identifiers, names, values, categories, and explanations for each score.

    Attributes:
        id (str): Unique identifier for the feedback score.
        name (str): Name associated with the feedback score.
        value (float): The numerical value of the feedback score.
        category_name (Optional[str]): Category to which the feedback score belongs, if any.
        reason (Optional[str]): Reason or explanation for the feedback score, if available.
    """

    id: str
    name: str
    value: float
    category_name: Optional[str] = None
    reason: Optional[str] = None


@dataclasses.dataclass
class SpanModel:
    """
    Represents a span model used to describe specific points in a process, their metadata,
    and associated data.

    This class is used to store and manipulate structured data for events or
    spans, including metadata, time markers, associated input/output, tags,
    and additional properties. It serves as a representative structure for recording
    and organizing event-specific information, often used in applications like
    logging, distributed tracing, or data processing pipelines.

    Attributes:
        id (str): Unique identifier for the span.
        start_time (datetime.datetime): Start time of the span.
        name (Optional[str]): Name of the span, if provided.
        input (Optional[Dict[str, Any]]): Input data associated with the span, if any.
        output (Optional[Dict[str, Any]]): Output data associated with the span, if any.
        tags (Optional[List[str]]): List of tags linked to the span.
        metadata (Optional[Dict[str, Any]]): Additional metadata for the span.
        type (str): Type of the span, defaulting to "general".
        usage (Optional[Dict[str, Any]]): Usage-related information for the span.
        end_time (Optional[datetime.datetime]): End time of the span, if available.
        project_name (str): Name of the project the span is associated with,
            defaulting to a predefined project name.
        spans (List[SpanModel]): List of nested spans related to this span.
        feedback_scores (List[FeedbackScoreModel]): List of feedback scores associated
            with the span.
        model (Optional[str]): Model identification used, if applicable.
        provider (Optional[str]): Provider of the span or associated services, if any.
        error_info (Optional[ErrorInfoDict]): Error information or diagnostics
            for the span, if applicable.
        total_cost (Optional[float]): Total cost incurred associated with this span,
            if relevant.
        last_updated_at (Optional[datetime.datetime]): Timestamp of when the span was
            last updated, if available.
    """

    id: str
    start_time: datetime.datetime
    name: Optional[str] = None
    input: Optional[Dict[str, Any]] = None
    output: Optional[Dict[str, Any]] = None
    tags: Optional[List[str]] = None
    metadata: Optional[Dict[str, Any]] = None
    type: str = "general"
    usage: Optional[Dict[str, Any]] = None
    end_time: Optional[datetime.datetime] = None
    project_name: str = OPIK_PROJECT_DEFAULT_NAME
    spans: List["SpanModel"] = dataclasses.field(default_factory=list)
    feedback_scores: List[FeedbackScoreModel] = dataclasses.field(default_factory=list)
    model: Optional[str] = None
    provider: Optional[str] = None
    error_info: Optional[ErrorInfoDict] = None
    total_cost: Optional[float] = None
    last_updated_at: Optional[datetime.datetime] = None


@dataclasses.dataclass
class TraceModel:
    """
    Represents a trace model that encapsulates data about a trace, its related metadata,
    and associated spans. It is used for tracking and analyzing data during execution
    or processing tasks.

    This class provides a structure to represent trace information, including the start
    and end times, associated project details, input/output data, feedback scores, error
    information, and thread association. It is designed to handle optional fields for
    flexible use across various scenarios.

    Attributes:
        id (str): Unique identifier for the trace.
        start_time (datetime.datetime): Timestamp representing the start of the trace.
        name (Optional[str]): Optional name for the trace, which can provide a descriptive
            label.
        project_name (str): Name of the project associated with the trace.
        input (Optional[Dict[str, Any]]): Optional dictionary containing the input data
            associated with the trace.
        output (Optional[Dict[str, Any]]): Optional dictionary containing the output data
            generated by the trace.
        tags (Optional[List[str]]): Optional list of tags associated with the trace for
            classification or filtering purposes.
        metadata (Optional[Dict[str, Any]]): Optional metadata providing additional
            information about the trace.
        end_time (Optional[datetime.datetime]): Timestamp representing the end of the
            trace.
        spans (List[SpanModel]): List of spans associated with the trace, representing
            individual processing parts or segments within the trace.
        feedback_scores (List[FeedbackScoreModel]): List of feedback scores associated
            with the trace.
        error_info (Optional[ErrorInfoDict]): Optional dictionary containing information
            about errors encountered during the trace.
        thread_id (Optional[str]): Optional identifier of the thread associated with the
            trace.
        last_updated_at (Optional[datetime.datetime]): Timestamp for when the trace was
            last updated.
    """

    id: str
    start_time: datetime.datetime
    name: Optional[str]
    project_name: str
    input: Optional[Dict[str, Any]] = None
    output: Optional[Dict[str, Any]] = None
    tags: Optional[List[str]] = None
    metadata: Optional[Dict[str, Any]] = None
    end_time: Optional[datetime.datetime] = None
    spans: List[SpanModel] = dataclasses.field(default_factory=list)
    feedback_scores: List[FeedbackScoreModel] = dataclasses.field(default_factory=list)
    error_info: Optional[ErrorInfoDict] = None
    thread_id: Optional[str] = None
    last_updated_at: Optional[datetime.datetime] = None
