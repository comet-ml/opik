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

import logging

from . import hooks

LOGGER = logging.getLogger(__name__)

FRAMEWORK = "openai"


def _chat_completion_create(original, return_value, *args, **kwargs):
    try:
        hooks.chat_completion_create(
            original, return_value, *args, **kwargs
        )
    except Exception:
        LOGGER.debug("Failed to log ChatCompletion.create call data", exc_info=True)


# def patch(module_finder):
#     _chat_completion_create.allow_after_exception = True
#     _completion_create.allow_after_exception = True
#     _edit_create.allow_after_exception = True

#     monkey_patching.check_module("openai")
#     module_finder.register_after(
#         "openai",
#         "Completion.create",
#         _completion_create,
#     )
#     module_finder.register_after(
#         "openai", "ChatCompletion.create", _chat_completion_create
#     )
#     module_finder.register_after("openai", "Edit.create", _edit_create)


# monkey_patching.check_module("openai")
