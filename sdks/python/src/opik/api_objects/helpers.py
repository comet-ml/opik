import datetime
import logging
from typing import Any, List, Optional

import uuid_extensions

from .. import config, datetime_helpers, logging_messages

LOGGER = logging.getLogger(__name__)


def generate_id() -> str:
    return str(uuid_extensions.uuid7())


def datetime_to_iso8601_if_not_None(
    value: Optional[datetime.datetime],
) -> Optional[str]:
    if value is None:
        return None

    return datetime_helpers.datetime_to_iso8601(value)


def list_to_batches(items: List[Any], batch_size: int) -> List[List[Any]]:
    return [items[i : i + batch_size] for i in range(0, len(items), batch_size)]


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
