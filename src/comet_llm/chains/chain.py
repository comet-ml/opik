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

import collections
from typing import TYPE_CHECKING, Dict, List, Optional

from .. import datetimes
from ..types import JSONEncodable

if TYPE_CHECKING:  # pragma: no cover
    from . import node


class Chain:
    def __init__(self, inputs: JSONEncodable, metadata: Dict[str, JSONEncodable]):
        self._nodes: List["node.ChainNode"] = []
        self._node_names_registry: collections.defaultdict = collections.defaultdict(
            lambda: 0
        )
        self._inputs = inputs
        self._outputs: Optional[Dict[str, JSONEncodable]] = None

        self._metadata = metadata

        self._timer = datetimes.Timer()

    def track_node(self, node: "node.ChainNode") -> None:
        self._nodes.append(node)

    def set_outputs(
        self,
        outputs: Dict[str, JSONEncodable],
        metadata: Optional[Dict[str, JSONEncodable]] = None,
    ) -> None:
        self._timer.stop()
        self._outputs = outputs

        if metadata is not None:
            self._metadata.update(metadata)

    def as_dict(self) -> Dict[str, JSONEncodable]:
        chain_nodes = [chain_node.as_dict() for chain_node in self._nodes]

        chain_edges = []
        for i, chain_node in enumerate(self._nodes[:-1]):
            chain_edges.append([chain_node.id, self._nodes[i + 1].id])

        result = {
            "_version": "the-version",
            "chain_nodes": chain_nodes,
            "chain_edges": chain_edges,
            "chain_context": {},
            "chain_inputs": self._inputs,
            "chain_outputs": self._outputs,
            "metadata": self._metadata,
            "start_timestamp": self._timer.start_timestamp,
            "end_timestamp": self._timer.end_timestamp,
            "duration": self._timer.duration,
        }

        return result

    def generate_node_name(self, category: str) -> str:
        name = f"{category}-{self._node_names_registry[category]}"
        self._node_names_registry[category] += 1

        return name
