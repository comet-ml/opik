import logging
from typing import Optional

LOGGER = logging.getLogger(__name__)


def resolve_project_name(
    value_from_dataset: Optional[str],
    value_from_user: Optional[str],
    caller_name: str,
) -> Optional[str]:
    """
    Resolve which project name to use for evaluation.

    Prefers the dataset's ``project_name`` when set. If the caller also passed
    a ``project_name``, log a deprecation warning and ignore the override so
    traces land in the dataset's project.

    Falls back to the caller's ``project_name`` when the dataset has none, to
    preserve backward compatibility during the deprecation period.
    """
    if value_from_dataset is None:
        return value_from_user

    if value_from_user is not None:
        LOGGER.warning(
            "The `project_name` parameter of `%s()` is deprecated and will be "
            "removed in a future version. The dataset's project ('%s') will "
            "be used instead of the provided value ('%s').",
            caller_name,
            value_from_dataset,
            value_from_user,
        )

    return value_from_dataset
