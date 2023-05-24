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

from typing import Dict, Optional

from .. import convert
from ..types import JSONEncodable
from . import node


class Prompt(node.ChainNode):
    def __init__(
        self,
        prompt: JSONEncodable,
        name: Optional[str] = None,
        prompt_template: Optional[JSONEncodable] = None,
        prompt_template_variables: Optional[Dict[str, JSONEncodable]] = None,
        metadata: Optional[Dict[str, JSONEncodable]] = None,
    ):
        self._prompt_template = prompt_template
        self._prompt_template_variables = prompt_template_variables

        super().__init__(
            input=prompt, category="llm_call", name=name, metadata=metadata
        )

    def as_dict(self) -> Dict[str, JSONEncodable]:
        return convert.call_data_to_dict(
            prompt=self._input,
            outputs=self._outputs,
            id=self._id,
            metadata=self._metadata,
            prompt_template=self._prompt_template,
            prompt_template_variables=self._prompt_template_variables,
            category=self._category,
            start_timestamp=self._timer.start_timestamp,
            end_timestamp=self._timer.end_timestamp,
            duration=self._timer.duration,
        )
