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

# type: ignore

from . import subverter


def patch_psutil(psutil):
    original = psutil.cpu_count

    def patched(*args, **kwargs):
        print("Before psutil.cpu_count() call!")
        return original(*args, **kwargs)

    psutil.cpu_count = patched


_subverter = subverter.Subverter()
_subverter.register_import_callback("psutil", patch_psutil)

_subverter.hook_into_import_system()
