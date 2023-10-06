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

import dataclasses
from typing import List

from .types import AfterCallback, AfterExceptionCallback, BeforeCallback


@dataclasses.dataclass
class CallableExtenders:
    before: List[BeforeCallback] = dataclasses.field(default_factory=list)
    after: List[AfterCallback] = dataclasses.field(default_factory=list)
    after_exception: List[AfterExceptionCallback] = dataclasses.field(
        default_factory=list
    )
