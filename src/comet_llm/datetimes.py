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

import calendar
import datetime

_JAN1_2010 = datetime.datetime(2010, 1, 1).timestamp()
_JAN1_2030 = datetime.datetime(2030, 1, 1).timestamp()


def is_valid_timestamp_seconds(timestamp: float) -> bool:
    if timestamp < _JAN1_2010 or timestamp > _JAN1_2030:
        return False

    return True


def local_timestamp() -> int:
    now = datetime.datetime.utcnow()
    timestamp_in_seconds = calendar.timegm(now.timetuple()) + (now.microsecond / 1e6)
    timestamp_in_milliseconds = int(timestamp_in_seconds * 1000)
    return timestamp_in_milliseconds
