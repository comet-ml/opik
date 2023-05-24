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

from .. import convert, datetimes
from ..types import JSONEncodable
from . import state


class ChainNode:
    def __init__(
        self,
        input: JSONEncodable,
        category: str,
        name: Optional[str] = None,
        metadata: Optional[Dict[str, JSONEncodable]] = None,
    ):
        self._input = input
        self._outputs: Optional[Dict[str, JSONEncodable]] = None
        self._id = state.get_new_id()

        self._category = category
        self._name = name
        self._metadata = metadata if metadata is not None else {}

        self._timer = datetimes.Timer()

        state.get_global_chain().track_node(self)

    @property
    def id(self) -> int:  # pragma: no cover
        return self._id

    def __enter__(self) -> "ChainNode":
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:  # type: ignore
        self._timer.stop()

    def set_outputs(
        self,
        outputs: Dict[str, JSONEncodable],
        metadata: Optional[Dict[str, JSONEncodable]] = None,
    ) -> None:
        self._outputs = outputs
        if metadata is not None:
            self._metadata.update(metadata)

    def as_dict(self) -> Dict[str, JSONEncodable]:
        return convert.call_data_to_dict(
            prompt=self._input,
            outputs=self._outputs,
            id=self._id,
            metadata=self._metadata,
            prompt_template=None,
            prompt_template_variables=None,
            start_timestamp=self._timer.start_timestamp,
            end_timestamp=self._timer.end_timestamp,
            duration=self._timer.duration,
        )
