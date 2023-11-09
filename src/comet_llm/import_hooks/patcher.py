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

import inspect
from types import ModuleType
from typing import TYPE_CHECKING, Any

from . import wrapper

if TYPE_CHECKING:  # pragma: no cover
    from . import module_extension

# _get_object and _set_object copied from comet_ml.monkeypatching almost without any changes.


def _get_object(module: ModuleType, callable_path: str) -> Any:
    current_object = module

    for part in callable_path:
        try:
            current_object = getattr(current_object, part)
        except Exception:
            return None

    return current_object


def _set_object(
    module: ModuleType, callable_path: str, original: Any, new_object: Any
) -> None:
    object_to_patch = _get_object(module, callable_path[:-1])

    original_self = getattr(original, "__self__", None)

    # Support classmethod
    if original_self and inspect.isclass(original_self):
        new_object = classmethod(new_object)

    setattr(object_to_patch, callable_path[-1], new_object)


def patch(
    module: ModuleType, module_extension: "module_extension.ModuleExtension"
) -> None:
    for callable_name, callable_extenders in module_extension.items():
        callable_path = callable_name.split(".")
        original = _get_object(module, callable_path)

        if original is None:
            continue

        new_callable = wrapper.wrap(original, callable_extenders)
        _set_object(module, callable_path, original, new_callable)
