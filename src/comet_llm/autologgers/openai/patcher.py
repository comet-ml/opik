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

from typing import TYPE_CHECKING
from . import hooks

if TYPE_CHECKING:  # pragma: no cover
    from comet_llm.import_hooks import registry

def patch(registry: "registry.Registry") -> None:
    registry.register_before("openai", "ChatCompletion.create", hooks.before_chat_completion_create)
    registry.register_after("openai", "ChatCompletion.create", hooks.after_chat_completion_create)
    registry.register_after_exception("openai", "ChatCompletion.create", hooks.after_exception_chat_completion_create)