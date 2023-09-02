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

from typing import List


class Context:
    def __init__(self) -> None:
        self._stack: List[int] = []

    def add(self, span_id: int) -> None:
        self._stack.append(span_id)

    def pop(self) -> None:
        if len(self._stack) > 0:
            self._stack.pop()

    def current(self) -> List[int]:
        return list(self._stack)
