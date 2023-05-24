from typing import Dict, Optional

from .. import datetimes
from ..types import JSONEncodable
from . import node


class Chain:
    def __init__(self, inputs: JSONEncodable, metadata: Dict[str, JSONEncodable]):
        self._nodes = []
        self._inputs = inputs
        self._outputs = None

        self._metadata = metadata

        self._timer = datetimes.Timer()

    def track_node(self, node: node.ChainNode):
        self._nodes.append(node)

    def set_outputs(
        self,
        outputs: Dict[str, JSONEncodable],
        metadata: Optional[Dict[str, JSONEncodable]] = None,
    ):
        self._timer.stop()
        self._outputs = outputs
        self._metadata.update(metadata)

    def as_dict(
        self,
    ) -> Dict[str, JSONEncodable]:
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
