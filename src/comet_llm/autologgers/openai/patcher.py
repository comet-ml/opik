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

from typing import TYPE_CHECKING

from . import hooks

if TYPE_CHECKING:  # pragma: no cover
    from comet_llm.import_hooks import registry


def patch(registry: "registry.Registry") -> None:
    registry.register_before(
        "openai", "ChatCompletion.create", hooks.before_chat_completion_create
    )
    registry.register_after(
        "openai", "ChatCompletion.create", hooks.after_chat_completion_create
    )
    registry.register_after_exception(
        "openai", "ChatCompletion.create", hooks.after_exception_chat_completion_create
    )

    registry.register_before(
        "openai.resources.chat.completions",
        "Completions.create",
        hooks.before_chat_completion_create,
    )
    registry.register_after(
        "openai.resources.chat.completions",
        "Completions.create",
        hooks.after_chat_completion_create,
    )
    registry.register_after_exception(
        "openai.resources.chat.completions",
        "Completions.create",
        hooks.after_exception_chat_completion_create,
    )
