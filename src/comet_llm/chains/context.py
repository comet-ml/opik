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

from typing import TYPE_CHECKING, List

if TYPE_CHECKING:  # pragma: no cover
    from . import span


class Context:
    def __init__(self) -> None:
        self._stack: List["span.Span"] = []

    def add(self, span: "span.Span") -> None:
        self._stack.append(span)

    def pop(self) -> None:
        if len(self._stack) > 0:
            self._stack.pop()

    def current(self) -> List[int]:
        return [span.id for span in self._stack]
