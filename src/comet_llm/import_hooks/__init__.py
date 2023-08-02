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

from . import finder, registry


def print_message(original, return_value, *args, **kwargs):
    print("Before psutil.cpu_count() call!")


_registry = registry.Registry()

_registry.register_after("psutil", "cpu_count", print_message)
_finder = finder.Finder(_registry)


_finder.hook_into_import_system()
