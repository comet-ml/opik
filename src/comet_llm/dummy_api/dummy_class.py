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

# type: ignore

from typing import Any


def _dummy_callable(*args, **kwargs):
    pass


class DummyClass:
    def __init__(self, *args, **kwargs) -> None:
        pass

    def __setattr__(self, __name: str, __value: Any) -> None:
        pass

    def __getattribute__(self, __name: str) -> None:
        return _dummy_callable

    def __enter__(self) -> "DummyClass":
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:  # type: ignore
        pass
