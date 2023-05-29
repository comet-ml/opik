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
#  This file can not be copied and/or distributed without the express
#  permission of Comet ML Inc.
# *******************************************************

from typing import TYPE_CHECKING, Optional

from .. import exceptions

if TYPE_CHECKING:  # pragma: no cover
    from . import chain

_CHAIN: Optional["chain.Chain"] = None
_ID = 0


def get_global_chain() -> "chain.Chain":
    global _CHAIN
    if _CHAIN is None:
        raise exceptions.CometLLMException(
            "Global chain is not initialized. Initialize it with `comet_llm.start_chain(...)`"
        )

    return _CHAIN


def get_new_id() -> int:
    global _ID
    _ID += 1
    return _ID
