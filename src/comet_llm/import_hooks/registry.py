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

from typing import Any, Callable, Dict, List

from . import callable_extenders, module_extension


class Registry:
    def __init__(self) -> None:
        self._modules_extensions: Dict[str, module_extension.ModuleExtension] = {}

    @property
    def module_names(self):  # type: ignore
        return self._modules_extensions.keys()

    def get_extension(self, module_name: str) -> module_extension.ModuleExtension:
        return self._modules_extensions[module_name]

    def _get_callable_extenders(
        self, module_name: str, callable_name: str
    ) -> callable_extenders.CallableExtenders:
        extension = self._modules_extensions.setdefault(
            module_name, module_extension.ModuleExtension()
        )
        return extension.extenders(callable_name)

    def register_before(
        self, module_name: str, callable_name: str, patcher_function: Callable
    ) -> None:
        """
        patcher_function: Callable with the following signature
            func(
                original,
                *args,
                **kwargs
            )
        original - original callable to patch

        Return value of patcher function is expected to be either None
        or [Args,Kwargs] tuple to overwrite original args and kwargs
        """
        extenders = self._get_callable_extenders(module_name, callable_name)
        extenders.before.append(patcher_function)

    def register_after(
        self, module_name: str, callable_name: str, patcher_function: Callable
    ) -> None:
        """
        patcher_function: Callable with the following signature
            func(
                original,
                return_value,
                *args,
                **kwargs
            )
        original - original callable to patch
        return_value - value returned by original callable

        Return value of patcher function will overwrite return_value of
        patched function if not None
        """
        extenders = self._get_callable_extenders(module_name, callable_name)
        extenders.after.append(patcher_function)

    def register_after_exception(
        self, module_name: str, callable_name: str, patcher_function: Callable
    ) -> None:
        """
        patcher_function: Callable with the following signature
            func(
                original,
                exception,
                *args,
                **kwargs
            )
        original - original callable to patch
        exception - exception thrown from original callable

        Expected to return None.
        """
        extenders = self._get_callable_extenders(module_name, callable_name)
        extenders.after_exception.append(patcher_function)
