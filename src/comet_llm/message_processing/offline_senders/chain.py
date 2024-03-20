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

import json
import pathlib

from .. import messages


def send(message: messages.ChainMessage, file_name: str) -> None:
    to_dump = {"type": "ChainMessage", "message": message.to_dict()}
    with open(file_name, mode="at", encoding="utf-8") as out_stream:
        out_stream.write(json.dumps(to_dump) + "\n")

    return None
