import datetime
import logging
from typing import Optional, Dict, Any

from opik import llm_usage

from .. import config, datetime_helpers, logging_messages
from ..id_helpers import generate_id  # noqa: F401 , keep it here for backward compatibility with external dependants

LOGGER = logging.getLogger(__name__)


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
) -> Optional[Dict[str, Any]]:
    if usage is None and metadata is None:
        return None

    if usage is None:
        return metadata

    if metadata is None:
        metadata = {}

    if isinstance(usage, llm_usage.OpikUsage):
        metadata["usage"] = usage.provider_usage.model_dump(exclude_none=True)
        return metadata

    metadata["usage"] = usage
    return metadata
