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

import logging
from typing import TYPE_CHECKING, Dict, List, Optional

from comet_llm import logging as comet_logging

from .. import config, datetimes, exceptions, logging_messages
from ..types import JSONEncodable
from . import deepmerge, state

if TYPE_CHECKING:
    from . import chain

LOGGER = logging.getLogger(__name__)


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
            inputs: Span inputs.
            category: Span category.
            name: Span name. If not set will be
                generated automatically.
            metadata: Span metadata.
        """
        self._inputs = inputs
        self._category = category
        self._metadata = metadata if metadata is not None else {}
        self._outputs: Optional[Dict[str, JSONEncodable]] = None
        self._context: Optional[List[int]] = None
        self._chain: Optional["chain.Chain"] = None

        self._id = state.get_new_id()
        self._name = name if name is not None else "unnamed"
        self._timer = datetimes.Timer()

    def _connect_to_chain(self, chain: "chain.Chain") -> None:
        chain.track_node(self)
        self._context = chain.context.current()
        self._name = (
            self._name
            if self._name != "unnamed"
            else chain.generate_node_name(self._category)
        )
        self._chain = chain

    @property
    def id(self) -> int:  # pragma: no cover
        return self._id

    @property
    def name(self) -> str:  # pragma: no cover
        return self._name

    def __enter__(self) -> "Span":
        chain = state.get_global_chain()

        if chain is None:
            chain_not_initialized_exception = exceptions.CometLLMException(
                logging_messages.GLOBAL_CHAIN_NOT_INITIALIZED % "`Span`"
            )
            if config.raising_enabled():
                raise chain_not_initialized_exception

            comet_logging.log_once_at_level(
                LOGGER, logging.ERROR, str(chain_not_initialized_exception)
            )

            return self

        self.__api__start__(chain)
        return self

    def __api__start__(self, chain: "chain.Chain") -> None:
        self._connect_to_chain(chain)

        self._timer.start()
        self._chain.context.add(self.id)  # type: ignore

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:  # type: ignore
        self.__api__end__()

    def __api__end__(self) -> None:
        if self._chain is not None:
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
            outputs: Outputs.
            metadata: Span metadata.
                If metadata dictionary was passed to __init__ method,
                it will be updated.
        """
        self._outputs = outputs
        if metadata is not None:
            self._metadata = deepmerge.deepmerge(self._metadata, metadata)

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
