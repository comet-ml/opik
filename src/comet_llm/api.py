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
#  This file can not be copied and/or distributed without the express
#  permission of Comet ML Inc.
# *******************************************************

import logging
from typing import Optional

from . import experiment_info
from .experiment_api import comet_api_client, request_exception_wrapper
from . import logging_messages


LOGGER = logging.getLogger(__name__)


def log_user_feedback(id: str, score: float, api_key: Optional[str] = None) -> None:
    """
    Logs user feedback to the provided Prompt/Chain/Experiment ID.

    Args:
        id: str (required) the ID of the experiment, chain, prompt.
        socre: float (required) the feedback score, can be either 0, 0.0, 1, 1.0.
    """
    ALLOWED_SCORES = [0.0, 1.0]

    if not score in ALLOWED_SCORES:
        LOGGER.error(logging_messages.NON_ALLOWED_SCORE)
        return

    info = experiment_info.get(
        api_key,
        api_key_not_found_message=logging_messages.API_KEY_NOT_FOUND_MESSAGE % "log_user_feedback",
    )
    comet_client = comet_api_client.get(info.api_key)

    request_exception_wrapper.wrap(comet_client.log_experiment_metric(id, "user_feedback", score))