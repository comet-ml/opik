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
import logging
import threading
from typing import DefaultDict

LOGGER = logging.getLogger(__name__)


class Summary:
    def __init__(self) -> None:
        self._logs_registry: DefaultDict[str, int] = collections.defaultdict(lambda: 0)
        self._failed = 0
        self._lock = threading.Lock()

    def add_log(self, project_url: str, name: str) -> None:
        with self._lock:
            if len(self._logs_registry) == 0:
                LOGGER.info("%s logged to %s", name.capitalize(), project_url)

            self._logs_registry[project_url] += 1

    def increment_failed(self) -> None:
        with self._lock:
            self._failed += 1

    def print(self) -> None:
        for project, logs_amount in self._logs_registry.items():
            LOGGER.info("%d prompts and chains logged to %s", logs_amount, project)

        if self._failed > 0:
            LOGGER.info(
                "%d prompts and chains were not logged because of errors", self._failed
            )
