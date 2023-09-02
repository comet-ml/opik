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

import threading
from typing import Any, Dict


class ThreadContextRegistry:
    def __init__(self) -> None:
        self._registry: Dict[str, Any] = {}
        self._lock = threading.Lock()

    def get(self, key: str) -> Any:
        thread_wise_key = _thread_wise_key(key)
        with self._lock:
            if thread_wise_key not in self._registry:
                return None

            return self._registry[thread_wise_key]

    def add(self, key: str, value: Any) -> None:
        thread_wise_key = _thread_wise_key(key)
        with self._lock:
            self._registry[thread_wise_key] = value


def _thread_wise_key(key: str) -> str:
    return f"{key}-{threading.get_ident()}"
