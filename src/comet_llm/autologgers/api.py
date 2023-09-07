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

import comet_llm.config

from ..import_hooks import finder, registry
from .openai import patcher as openai_patcher


def patch() -> None:
    if comet_llm.config.autologging_enabled() and not comet_llm.config.comet_disabled():
        registry_ = registry.Registry()

        openai_patcher.patch(registry_)

        module_finder = finder.CometFinder(registry_)
        module_finder.hook_into_import_system()
