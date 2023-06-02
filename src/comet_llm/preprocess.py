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

from typing import Optional, Tuple

from . import datetimes, exceptions


def _raise_if_invalid_timestamps(
    start_timestamp: Optional[float], end_timestamp: Optional[float]
) -> None:
    if start_timestamp is not None:
        if not datetimes.is_valid_timestamp_seconds(start_timestamp):
            raise exceptions.CometLLMException(
                "Invalid start_timestamp: {start_timestamp}. Timestamp must be in seconds if specified."
            )

    if end_timestamp is not None:
        if not datetimes.is_valid_timestamp_seconds(end_timestamp):
            raise exceptions.CometLLMException(
                "Invalid end_timestamp: {end_timestamp}. Timestamp must be in seconds if specified."
            )

    if (
        start_timestamp is not None
        and end_timestamp is not None
        and start_timestamp > end_timestamp
    ):
        raise exceptions.CometLLMException(
            "Invalid timestamps. start_timestamp cannot be greater than end_timestamp."
        )


def timestamps(
    start_timestamp: Optional[float], end_timestamp: Optional[float]
) -> Tuple[Optional[float], Optional[float]]:
    _raise_if_invalid_timestamps(start_timestamp, end_timestamp)

    if start_timestamp is not None:
        start_timestamp *= 1000

    if end_timestamp is not None:
        end_timestamp *= 1000

    return start_timestamp, end_timestamp
