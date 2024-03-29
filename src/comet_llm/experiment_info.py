# -*- coding: utf-8 -*-
# *******************************************************
#   ____                     _               _
#  / ___|___  _ __ ___   ___| |_   _ __ ___ | |
# | |   / _ \| '_ ` _ \ / _ \ __| | '_ ` _ \| |
# | |__| (_) | | | | | |  __/ |_ _| | | | | | |
#  \____\___/|_| |_| |_|\___|\__(_)_| |_| |_|_|
#
#  Sign up for free at https://www.comet.com
#  Copyright (C) 2015-2023 Comet ML INC
#  This source code is licensed under the MIT license found in the
#  LICENSE file in the root directory of this package.
# *******************************************************

import dataclasses
from typing import Optional

from . import config, exceptions

DEFAULT_PROJECT_NAME = "llm-general"


@dataclasses.dataclass
class ExperimentInfo:
    # If you have a better name for this class and module, you are welcome to suggest
    api_key: Optional[str]
    workspace: Optional[str]
    project_name: Optional[str]


def get(
    api_key: Optional[str] = None,
    workspace: Optional[str] = None,
    project_name: Optional[str] = None,
    api_key_not_found_message: Optional[str] = None,
) -> ExperimentInfo:
    """
    Use this function when you work with an API
    that accepts api_key, project or workspace.
    """
    api_key = api_key if api_key else config.api_key()

    if api_key is None and api_key_not_found_message is not None:
        raise exceptions.CometLLMException(
            api_key_not_found_message, log_message_once=True
        )

    if api_key is not None:
        config.setup_comet_url(api_key)

    workspace = workspace if workspace else config.workspace()
    project_name = project_name if project_name else config.project_name()

    project_name = project_name if project_name else DEFAULT_PROJECT_NAME

    return ExperimentInfo(api_key, workspace, project_name)
