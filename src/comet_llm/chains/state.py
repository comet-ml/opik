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

import inspect
import threading
from typing import TYPE_CHECKING, Dict, Optional

from .. import app, config, exceptions
from . import thread_context_registry

if TYPE_CHECKING:  # pragma: no cover
    from . import chain


class State:
    def __init__(self) -> None:
        self._id: int = 0
        self._thread_context_registry = thread_context_registry.ThreadContextRegistry()
        self._lock = threading.Lock()

    def chain_exists(self) -> bool:
        return self._thread_context_registry.get("global-chain") is not None

    @property
    def chain(self) -> "chain.Chain":
        result: "chain.Chain" = self._thread_context_registry.get("global-chain")
        return result

    @chain.setter
    def chain(self, value: "chain.Chain") -> None:
        self._thread_context_registry.add("global-chain", value)

    def new_id(self) -> int:
        with self._lock:
            self._id += 1
            return self._id


_APP_STATE = State()


def global_chain_exists() -> bool:
    return _APP_STATE.chain_exists()


def get_global_chain() -> "chain.Chain":
    return _APP_STATE.chain


def set_global_chain(new_chain: "chain.Chain") -> None:
    _APP_STATE.chain = new_chain


def get_new_id() -> int:
    return _APP_STATE.new_id()
