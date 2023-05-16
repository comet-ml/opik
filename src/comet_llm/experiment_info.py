import dataclasses
from typing import Optional

from . import config


@dataclasses.dataclass
class ExperimentInfo:
    api_key: str
    workspace: Optional[str]
    project_name: Optional[str]


def get(
    api_key: Optional[str],
    workspace: Optional[str],
    project_name: Optional[str],
    raise_if_api_key_not_found: Exception,
):
    api_key = api_key if api_key else config.api_key()
    if api_key is None:
        raise raise_if_api_key_not_found

    workspace = workspace if workspace else config.workspace()
    project_name = project_name if project_name else config.project_name()

    return ExperimentInfo(api_key, workspace, project_name)
