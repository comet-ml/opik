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

from .. import datetimes
from ..types import JSONEncodable
from . import state


class Span:
    """
    A single unit of Chain that has its own
    context. Spans can be nested, in that case inner one
    exist in the context of the outer(parent) one.
    Outer Span is considered to be a parent for an inner one.
    """

    def __init__(
        self,
        inputs: JSONEncodable,
        category: str,
        name: Optional[str] = None,
        metadata: Optional[Dict[str, JSONEncodable]] = None,
    ):
        """
        Args:
            inputs: JSONEncodable (required), span inputs.
            category: str (required), span category.
            name: str (optional), span name. If not set will be
                generated automatically.
            metadata: Dict[str, JSONEncodable] (optional), span metadata.
        """
        self._inputs = inputs
        self._category = category
        self._metadata = metadata if metadata is not None else {}
        self._outputs: Optional[Dict[str, JSONEncodable]] = None
        self._id = state.get_new_id()

        self._connect_to_chain()
        self._name = (
            name if name is not None else self._chain.generate_node_name(category)
        )

        self._timer = datetimes.Timer()

    def _connect_to_chain(self) -> None:
        chain = state.get_global_chain()
        chain.track_node(self)
        self._context = chain.context.current()
        self._chain = chain

    @property
    def id(self) -> int:  # pragma: no cover
        return self._id

    @property
    def name(self) -> str:  # pragma: no cover
        return self._name

    def __enter__(self) -> "Span":
        self._timer.start()
        self._chain.context.add(self.id)
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:  # type: ignore
        self._timer.stop()
        self._chain.context.pop()

    def set_outputs(
        self,
        outputs: Dict[str, JSONEncodable],
        metadata: Optional[Dict[str, JSONEncodable]] = None,
    ) -> None:
        """
        Sets outputs to span object.
        Args:
            outputs: Dict[str, JSONEncodable] (required), outputs
            metadata: Dict[str, JSONEncodable] (optional), span metadata.
                If metadata dictionary was passed to __init__ method,
                it will be updated.
        """
        self._outputs = outputs
        if metadata is not None:
            self._metadata.update(metadata)

    def as_dict(self) -> Dict[str, JSONEncodable]:
        inputs = self._inputs
        outputs = self._outputs

        inputs = inputs if isinstance(inputs, dict) else {"input": inputs}
        outputs = outputs if isinstance(outputs, dict) else {"output": outputs}

        return {
            "id": self._id,
            "category": self._category,
            "name": self._name,
            "inputs": inputs,
            "outputs": outputs,
            "duration": self._timer.duration,
            "start_timestamp": self._timer.start_timestamp,
            "end_timestamp": self._timer.end_timestamp,
            "parent_ids": self._context,
            "metadata": self._metadata,
        }
