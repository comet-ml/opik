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

import sys
from importlib import machinery
from types import ModuleType
from typing import List, Optional

from . import module_loader, registry


class CometFinder:
    def __init__(self, extensions_registry: registry.Registry) -> None:
        self._registry = extensions_registry
        self._pathfinder = machinery.PathFinder()

    def hook_into_import_system(self) -> None:
        if self not in sys.meta_path:
            sys.meta_path.insert(0, self)  # type: ignore

    def find_spec(
        self, fullname: str, path: Optional[List[str]], target: Optional[ModuleType]
    ) -> Optional[machinery.ModuleSpec]:
        if fullname not in self._registry.module_names:
            return None

        original_spec = self._pathfinder.find_spec(fullname, path, target)

        if original_spec is None:
            return None

        return self._wrap_spec_loader(fullname, original_spec)

    def _wrap_spec_loader(
        self, fullname: str, spec: machinery.ModuleSpec
    ) -> machinery.ModuleSpec:
        module_extension = self._registry.get_extension(fullname)
        spec.loader = module_loader.CometModuleLoader(fullname, spec.loader, module_extension)  # type: ignore
        return spec
