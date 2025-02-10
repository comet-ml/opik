import datetime
import logging
from typing import Optional, List

import uuid6

from ..rest_api.types import trace_public, span_public, feedback_score_public
from .. import config, datetime_helpers, logging_messages

LOGGER = logging.getLogger(__name__)


def generate_id() -> str:
    return str(uuid6.uuid7())


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


def feedback_scores_public_to_feedback_scores_dict(
    feedback_scores_public: List[feedback_score_public.FeedbackScorePublic],
    include_id: bool = False,
) -> List[dict]:
    feedback_scores = []
    for feedback_score in feedback_scores_public:
        score = {"name": feedback_score["name"], "value": feedback_score["value"]}
        if feedback_score["reason"]:
            score["reason"] = feedback_score["reason"]

        if feedback_score["category_name"]:
            score["category_name"] = feedback_score["category_name"]

        if include_id:
            score["id"] = feedback_score["id"]

        feedback_scores.append(score)

    return feedback_scores


def trace_public_to_trace_dict(
    trace: trace_public.TracePublic, include_id: bool = False
) -> dict:
    new_trace = trace.model_dump()

    if not include_id:
        del new_trace["id"]

    feedback_scores = new_trace.get("feedback_scores") or []
    new_trace["feedback_scores"] = feedback_scores_public_to_feedback_scores_dict(
        feedback_scores, include_id=include_id
    )

    return new_trace


def span_public_to_span_dict(
    span: span_public.SpanPublic, include_id: bool = False
) -> dict:
    new_span = span.model_dump()

    if not include_id:
        del new_span["id"]

    feedback_scores = new_span.get("feedback_scores") or []
    new_span["feedback_scores"] = feedback_scores_public_to_feedback_scores_dict(
        feedback_scores, include_id=include_id
    )

    return new_span
