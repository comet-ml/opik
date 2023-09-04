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

from . import logs_registry

LOGGER = logging.getLogger(__name__)


class Summary:
    def __init__(self) -> None:
        self._registry = logs_registry.LogsRegistry()

    def add_log(self, project_url: str, name: str) -> None:
        if self._registry.empty():
            LOGGER.info("%s logged to %s", name.capitalize(), project_url)

        self._registry.register_log(project_url)

    def print(self) -> None:
        registry_items = self._registry.as_dict().items()

        for project, logs_amount in registry_items:
            LOGGER.info("%d prompts and chains logged to %s", logs_amount, project)
