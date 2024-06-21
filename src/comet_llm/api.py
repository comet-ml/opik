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

import logging
from typing import Optional

from . import config, exceptions, experiment_info, logging_messages
from .experiment_api import ExperimentAPI

LOGGER = logging.getLogger(__name__)


@exceptions.filter(allow_raising=config.raising_enabled())
def log_user_feedback(id: str, score: float, api_key: Optional[str] = None) -> None:
    """
    Logs user feedback for the provided Prompt or Chain ID. This will
    overwrite any previously set value.

    Args:
        id: The ID of the Prompt or Chain.
        score: The feedback score, can be either 0, 0.0, 1, or 1.0.
        api_key: Comet API key.
    """
    ALLOWED_SCORES = [0.0, 1.0]

    if score not in ALLOWED_SCORES:
        LOGGER.error(logging_messages.NON_ALLOWED_SCORE)
        return

    info = experiment_info.get(
        api_key,
        api_key_not_found_message=logging_messages.API_KEY_NOT_FOUND_MESSAGE
        % "log_user_feedback",
    )
    experiment_api = ExperimentAPI.from_existing_id(
        id=id,
        api_key=info.api_key,  # type: ignore
        load_metadata=False,
    )

    experiment_api.log_metric("user_feedback", score)


def flush() -> None:
    """Flush all data to Comet platform."""
    pass
