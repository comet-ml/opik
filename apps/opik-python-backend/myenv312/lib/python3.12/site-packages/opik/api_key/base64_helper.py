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
#  This file can not be copied and/or distributed
#  without the express permission of Comet ML Inc.
# *******************************************************
import base64


def decode_base64(data: str, fix_padding: bool = True) -> bytes:
    if fix_padding:
        missing_padding = len(data) % 4
        if missing_padding and data.endswith("="):
            # wrong padding
            data = data.replace("=", "")
            return decode_base64(data)

        if missing_padding:
            data += "=" * (4 - missing_padding)
    return base64.b64decode(data, validate=True)
