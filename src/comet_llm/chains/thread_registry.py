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
from typing import TYPE_CHECKING, Dict, Optional

if TYPE_CHECKING:
    from .chain import Chain


class ChainThreadRegistry:
    def __init__(self) -> None:
        self._threads_chains: Dict[int, "Chain"] = {}
        self._lock = threading.Lock()

    def get(self) -> Optional["Chain"]:
        thread_id = threading.get_ident()
        with self._lock:
            if thread_id not in self._threads_chains:
                return None

            return self._threads_chains[thread_id]

    def add(self, new_chain: "Chain") -> None:
        thread_id = threading.get_ident()
        with self._lock:
            self._threads_chains[thread_id] = new_chain
