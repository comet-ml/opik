# -*- coding: utf-8 -*-
# *******************************************************
#   ____                     _               _
#  / ___|___  _ __ ___   ___| |_   _ __ ___ | |
# | |   / _ \| '_ ` _ \ / _ \ __| | '_ ` _ \| |
# | |__| (_) | | | | | |  __/ |_ _| | | | | | |
#  \____\___/|_| |_| |_|\___|\__(_)_| |_| |_|_|
#
#  Sign up for free at https://www.comet.com
#  Copyright (C) 2015-2024 Comet ML INC
#  This source code is licensed under the MIT license found in the
#  LICENSE file in the root directory of this package.
# *******************************************************

from typing import Union

from .. import config
from . import offline_message_processor, online_message_processor

MESSAGE_PROCESSOR: Union[
    offline_message_processor.OfflineMessageProcessor,
    online_message_processor.OnlineMessageProcessor,
]

if config.offline_enabled():
    MESSAGE_PROCESSOR = offline_message_processor.OfflineMessageProcessor(
        offline_directory=config.offline_directory(),
        file_usage_duration=config.offline_batch_duration_seconds(),
    )
else:
    MESSAGE_PROCESSOR = online_message_processor.OnlineMessageProcessor()
