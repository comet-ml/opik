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

import functools
from typing import TYPE_CHECKING, Any, Callable, Optional

from comet_llm.chains import thread_context_registry

if TYPE_CHECKING:
    from comet_llm.chains.chain import Chain
    from comet_llm.chains.span import Span


class OpenAIContext:
    def __init__(self) -> None:
        self._thread_context_registry = thread_context_registry.ThreadContextRegistry()

    @property
    def chain(self) -> Optional["Chain"]:
        return self._thread_context_registry.get("chain")  # type: ignore

    @chain.setter
    def chain(self, value: "Chain") -> None:
        self._thread_context_registry.add("chain", value)

    @property
    def span(self) -> Optional["Span"]:
        return self._thread_context_registry.get("span")  # type: ignore

    @span.setter
    def span(self, value: "Span") -> None:
        self._thread_context_registry.add("span", value)

    def clear(self) -> None:
        self._thread_context_registry.add("span", None)
        self._thread_context_registry.add("chain", None)


def clear_on_end(function: Callable) -> Callable:
    @functools.wraps(function)
    def wrapped(*args, **kwargs) -> Any:  # type: ignore
        try:
            return function(*args, **kwargs)
        finally:
            CONTEXT.clear()

    return wrapped


CONTEXT = OpenAIContext()
