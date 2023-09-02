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

import collections
from typing import DefaultDict, Dict


class LogsRegistry:
    def __init__(self) -> None:
        self._registry: DefaultDict[str, int] = collections.defaultdict(lambda: 0)

    def register_log(self, project_url: str) -> None:
        self._registry[project_url] += 1

    def as_dict(self) -> Dict[str, int]:
        return self._registry.copy()

    def empty(self) -> bool:
        return len(self._registry) == 0
