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

from . import app, autologgers, config, logging
from .api_objects.api import API
from .config import init, is_ready

if config.comet_disabled():
    from .dummy_api import Span, end_chain, log_prompt, start_chain  # type: ignore
else:
    from .api import flush, log_user_feedback
    from .chains.api import end_chain, start_chain
    from .chains.span import Span
    from .prompts.api import log_prompt


__all__ = [
    "log_prompt",
    "start_chain",
    "end_chain",
    "Span",
    "init",
    "is_ready",
    "log_user_feedback",
    "flush",
    "API",
]

logging.setup()
app.register_summary_print()
autologgers.patch()
