# -*- coding: utf-8 -*-
# *******************************************************
#   ____                     _               _
#  / ___|___  _ __ ___   ___| |_   _ __ ___ | |
# | |   / _ \| '_ ` _ \ / _ \ __| | '_ ` _ \| |
# | |__| (_) | | | | | |  __/ |_ _| | | | | | |
#  \____\___/|_| |_| |_|\___|\__(_)_| |_| |_|_|
#
#  Sign up for free at http://www.comet.ml
#  Copyright (C) 2015-2021 Comet ML INC
#  This file can not be copied and/or distributed without the express
#  permission of Comet ML Inc.
# *******************************************************

import comet_llm.config

from ..import_hooks import finder, registry
from .openai import patcher as openai_patcher


def patch() -> None:
    if comet_llm.config.autologging_enabled():
        registry_ = registry.Registry()

        openai_patcher.patch(registry_)

        module_finder = finder.CometFinder(registry_)
        module_finder.hook_into_import_system()
