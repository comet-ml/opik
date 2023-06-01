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
    from .. import experiment_info
    from . import chain


class State:
    def __init__(self) -> None:
        self._id: int = 0
        self._chain: Optional["chain.Chain"] = None

        self._experiment_info: Optional["experiment_info.ExperimentInfo"] = None

    @property
    def chain(self) -> "chain.Chain":
        if self._chain is None:
            raise exceptions.CometLLMException(
                "Global chain is not initialized. Initialize it with `comet_llm.start_chain(...)`"
            )

        return self._chain

    @chain.setter
    def chain(self, new_chain: "chain.Chain") -> None:
        self._chain = new_chain

    def new_id(self) -> int:
        self._id += 1
        return self._id


APP_STATE = State()


def get_global_chain() -> "chain.Chain":
    return APP_STATE.chain


def set_global_chain(new_chain: "chain.Chain") -> None:
    APP_STATE.chain = new_chain


def get_new_id() -> int:
    return APP_STATE.new_id()
