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
import functools

from typing import Optional, Tuple, Callable
from comet_llm.chains import chain, span

from comet_llm import experiment_info

class OpenAIContext:
    def __init__(self) -> None:
        self.chain: Optional[chain.Chain] = None
        self.span: Optional[span.Span] = None

    def clear(self) -> None:
        self.span = None
        self.chain = None


def clear_on_end(function: Callable):
    @functools.wraps(function)
    def wrapped(*args, **kwargs):
        try:
            return function(*args, **kwargs)
        finally:
            CONTEXT.clear()
    
    return wrapped

CONTEXT = OpenAIContext()
