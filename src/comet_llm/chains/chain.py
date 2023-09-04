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

import collections
from typing import TYPE_CHECKING, Dict, List, Optional

from .. import datetimes
from ..types import JSONEncodable
from . import context, deepmerge, version

if TYPE_CHECKING:  # pragma: no cover
    from ..experiment_info import ExperimentInfo
    from . import span


class Chain:
    def __init__(
        self,
        inputs: JSONEncodable,
        metadata: Optional[Dict[str, JSONEncodable]],
        experiment_info: "ExperimentInfo",
        tags: Optional[List[str]] = None,
        others: Optional[Dict[str, JSONEncodable]] = None,
    ):
        self._nodes: List["span.Span"] = []
        self._node_names_registry: collections.defaultdict = collections.defaultdict(
            lambda: 0
        )
        self._inputs = inputs
        self._outputs: Optional[Dict[str, JSONEncodable]] = None
        self._metadata = metadata if metadata is not None else {}
        self._context = context.Context()
        self._prepare_timer()

        self._experiment_info = experiment_info
        self._tags = tags
        self._others = others if others is not None else {}

    @property
    def experiment_info(self) -> "ExperimentInfo":  # pragma: no cover
        return self._experiment_info

    @property
    def tags(self) -> Optional[List[str]]:
        return self._tags

    @property
    def others(self) -> Dict[str, JSONEncodable]:
        return self._others

    @property
    def context(self) -> "context.Context":  # pragma: no cover
        return self._context

    def _prepare_timer(self) -> None:
        self._timer = datetimes.Timer()
        self._timer.start()

    def track_node(self, node: "span.Span") -> None:
        self._nodes.append(node)

    def set_outputs(
        self,
        outputs: Dict[str, JSONEncodable],
        metadata: Optional[Dict[str, JSONEncodable]] = None,
    ) -> None:
        self._timer.stop()
        self._outputs = outputs

        if metadata is not None:
            self._metadata = deepmerge.deepmerge(self._metadata, metadata)

    def as_dict(self) -> Dict[str, JSONEncodable]:
        chain_nodes = [chain_node.as_dict() for chain_node in self._nodes]

        inputs = self._inputs
        outputs = self._outputs

        inputs = inputs if isinstance(inputs, dict) else {"input": inputs}
        outputs = outputs if isinstance(outputs, dict) else {"output": outputs}

        result = {
            "version": version.ASSET_FORMAT_VERSION,
            "chain_nodes": chain_nodes,
            "chain_inputs": inputs,
            "chain_outputs": outputs,
            "metadata": self._metadata,
            "category": "chain",
            "start_timestamp": self._timer.start_timestamp,
            "end_timestamp": self._timer.end_timestamp,
            "chain_duration": self._timer.duration,
        }

        return result

    def generate_node_name(self, category: str) -> str:
        name = f"{category}-{self._node_names_registry[category]}"
        self._node_names_registry[category] += 1

        return name
