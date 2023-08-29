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

import threading
from typing import TYPE_CHECKING, Optional, Dict

from .. import exceptions

if TYPE_CHECKING:  # pragma: no cover
    from . import chain


class State:
    def __init__(self) -> None:
        self._id: int = 0
        self._chain: Optional["chain.Chain"] = None
        self._threads_chains: Dict[int, "chain.Chain"] = {}
        self._lock = threading.Lock()

    def get_chain(self, thread_id: int) -> "chain.Chain":
        with self._lock:
            if thread_id not in self._threads_chains:
                raise exceptions.CometLLMException(
                    "Global chain is not initialized for this thread. Initialize it with `comet_llm.start_chain(...)`"
                )

            return self._threads_chains[thread_id]

    def set_chain(self, thread_id: int, new_chain: "chain.Chain") -> None:
        with self._lock:
            self._threads_chains[thread_id] = new_chain

    def new_id(self) -> int:
        with self._lock:
            self._id += 1
            return self._id


_APP_STATE = State()


def get_global_chain() -> "chain.Chain":
    thread_id = threading.get_ident()
    return _APP_STATE.get_chain(thread_id)


def set_global_chain(new_chain: "chain.Chain") -> None:
    thread_id = threading.get_ident()
    _APP_STATE.set_chain(thread_id, new_chain)


def get_new_id() -> int:
    return _APP_STATE.new_id()
