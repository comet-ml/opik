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

from . import app, config, logging
from .chains.api import end_chain, start_chain
from .chains.span import Span
from .config import init
from .prompts.api import log_prompt

logging.setup()
app.register_summary_print()

if config.comet_disabled():
    from . import dummy_objects

    log_prompt = dummy_objects.dummy_callable
    start_chain = dummy_objects.dummy_callable
    end_chain = dummy_objects.dummy_callable
    Span = dummy_objects.DummyClass

__all__ = ["log_prompt", "start_chain", "end_chain", "Span", "init"]
