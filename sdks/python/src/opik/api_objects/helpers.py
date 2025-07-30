import datetime
import logging
from typing import Optional, Dict, Any, List, TypeVar, Type, Union

import opik.llm_usage as llm_usage
from . import opik_query_language, validation_helpers, constants

from .. import config, datetime_helpers, logging_messages
from ..id_helpers import generate_id  # noqa: F401 , keep it here for backward compatibility with external dependants
from ..message_processing import messages
from ..rest_api.types import (
    span_filter_public,
    trace_filter_public,
    trace_thread_filter,
)
from ..types import FeedbackScoreDict

LOGGER = logging.getLogger(__name__)


FilterParsedItemT = TypeVar(
    "FilterParsedItemT",
    bound=Union[
        span_filter_public.SpanFilterPublic,
        trace_filter_public.TraceFilterPublic,
        trace_thread_filter.TraceThreadFilter,
    ],
)
OptionalFilterParsedItemList = Optional[List[FilterParsedItemT]]

ScoreMessageT = TypeVar(
    "ScoreMessageT",
    bound=Union[messages.FeedbackScoreMessage, messages.ThreadsFeedbackScoreMessage],
)
OptionalScoreMessageList = Optional[List[ScoreMessageT]]


def datetime_to_iso8601_if_not_None(
    value: Optional[datetime.datetime],
) -> Optional[str]:
    if value is None:
        return None

    return datetime_helpers.datetime_to_iso8601(value)


def resolve_child_span_project_name(
    parent_project_name: Optional[str],
    child_project_name: Optional[str],
    show_warning: bool = True,
) -> Optional[str]:
    if parent_project_name != child_project_name:
        # if the user has specified a project name -> print warning
        if show_warning and child_project_name is not None:
            # if project name is None -> use default project name
            parent_project_name_msg = (
                parent_project_name
                if parent_project_name is not None
                else config.OPIK_PROJECT_DEFAULT_NAME
            )
            child_project_name_msg = (
                child_project_name
                if child_project_name is not None
                else config.OPIK_PROJECT_DEFAULT_NAME
            )

            LOGGER.warning(
                logging_messages.NESTED_SPAN_PROJECT_NAME_MISMATCH_WARNING_MESSAGE.format(
                    child_project_name_msg, parent_project_name_msg
                )
            )
        project_name = parent_project_name
    else:
        project_name = child_project_name

    return project_name


def add_usage_to_metadata(
    usage: Optional[Dict[str, Any]],
    metadata: Optional[Dict[str, Any]],
    create_metadata: bool = False,
) -> Optional[Dict[str, Any]]:
    if usage is None:
        return metadata

    if metadata is None and not create_metadata:
        return None

    metadata = {} if metadata is None else {**metadata}

    if isinstance(usage, llm_usage.OpikUsage):
        metadata["usage"] = usage.provider_usage.model_dump(exclude_none=True)
        return metadata

    metadata["usage"] = usage
    return metadata


def parse_filter_expressions(
    filter_string: Optional[str],
    parsed_item_class: Type[FilterParsedItemT],
) -> OptionalFilterParsedItemList:
    """
    Parses filter expressions from a filter string using a specified class for parsed items.

    This function takes a filter string and a class type for parsed items, parses the
    filter string into filter expressions using the OpikQueryLanguage, and then converts
    those filter expressions into a list of parsed items of the specified class. If the
    filter string does not contain any valid expressions, the function may return None.

    Args:
        filter_string: A string representing the filter expressions to be parsed.
        parsed_item_class: The class type to which the parsed filter expressions are mapped.

    Returns:
        Optional[List[T]]: A list of objects of type T created from the parsed filter
        expressions, or None if no valid expressions are found.
    """
    if filter_string is None:
        return None

    filter_expressions = opik_query_language.OpikQueryLanguage(
        filter_string
    ).get_filter_expressions()

    return parse_search_expressions(
        filter_expressions, parsed_item_class=parsed_item_class
    )


def parse_search_expressions(
    filter_expressions: Optional[List[Dict[str, Any]]],
    parsed_item_class: Type[FilterParsedItemT],
) -> OptionalFilterParsedItemList:
    if filter_expressions is None:
        return None

    return [parsed_item_class(**expression) for expression in filter_expressions]


def parse_feedback_score_messages(
    scores: List[FeedbackScoreDict],
    project_name: str,
    parsed_item_class: Type[ScoreMessageT],
    logger: logging.Logger,
) -> OptionalScoreMessageList:
    valid_scores = [
        score
        for score in scores
        if validation_helpers.validate_feedback_score(score, logger) is not None
    ]

    if len(valid_scores) == 0:
        return None

    score_messages = [
        parsed_item_class(
            source=constants.FEEDBACK_SCORE_SOURCE_SDK,
            project_name=project_name,
            **score_dict,
        )
        for score_dict in valid_scores
    ]

    return score_messages
