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

import importlib.abc
from types import ModuleType
from typing import TYPE_CHECKING, Callable, Optional

if TYPE_CHECKING:
    from importlib import machinery


class CallbackLoader(importlib.abc.Loader):
    def __init__(
        self,
        module_name: str,
        original_loader: importlib.abc.Loader,
        alert_callback: Callable,
    ) -> None:
        self._module_name = module_name
        self._original_loader = original_loader
        self._alert_callback = alert_callback

    def create_module(self, spec: "machinery.ModuleSpec") -> Optional[ModuleType]:
        if hasattr(self._original_loader, "create_module"):
            return self._original_loader.create_module(spec)

        LET_PYTHON_HANDLE_THIS = None
        return LET_PYTHON_HANDLE_THIS

    def exec_module(self, module: ModuleType) -> None:
        if hasattr(self._original_loader, "exec_module"):
            self._original_loader.exec_module(module)
        else:
            module = self._original_loader.load_module(self._module_name)

        self._alert_callback(module)
