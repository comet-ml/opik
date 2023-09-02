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

import calendar
import datetime
from typing import Optional

_JAN1_2010 = datetime.datetime(2010, 1, 1).timestamp()
_JAN1_2030 = datetime.datetime(2030, 1, 1).timestamp()


class Timer:
    def __init__(self) -> None:
        self._start_timestamp: Optional[int] = None
        self._end_timestamp: Optional[int] = None
        self._duration: Optional[int] = None

    def start(self) -> None:
        self._start_timestamp = local_timestamp()
        self._end_timestamp = None
        self._duration = None

    def stop(self) -> None:
        assert self._start_timestamp is not None
        self._end_timestamp = local_timestamp()
        self._duration = self._end_timestamp - self._start_timestamp

    @property
    def start_timestamp(self) -> Optional[int]:
        return self._start_timestamp

    @property
    def end_timestamp(self) -> Optional[int]:
        return self._end_timestamp

    @property
    def duration(self) -> Optional[int]:
        return self._duration


def is_valid_timestamp_seconds(timestamp: float) -> bool:
    if timestamp < _JAN1_2010 or timestamp > _JAN1_2030:
        return False

    return True


def local_timestamp() -> int:
    now = datetime.datetime.utcnow()
    timestamp_in_seconds = calendar.timegm(now.timetuple()) + (now.microsecond / 1e6)
    timestamp_in_milliseconds = int(timestamp_in_seconds * 1000)
    return timestamp_in_milliseconds
