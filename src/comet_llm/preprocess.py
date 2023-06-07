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

from typing import Optional

from . import datetimes, exceptions


def timestamp(timestamp: Optional[float]) -> float:
    if timestamp is None:
        return datetimes.local_timestamp()

    if not datetimes.is_valid_timestamp_seconds(timestamp):
        raise exceptions.CometLLMException(
            "Invalid timestamp: {timestamp}. Timestamp must be in seconds if specified."
        )

    return timestamp * 1000
