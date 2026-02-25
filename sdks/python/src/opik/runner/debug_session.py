"""Debug session state for graph-based stepping."""

import json
import logging
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from .graph_capture import GraphNode

LOGGER = logging.getLogger(__name__)


@dataclass
class DebugSession:
    session_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    trace_id: str = ""
    graph: List[Dict[str, Any]] = field(default_factory=list)
    cursor: int = 0
    agent_info: Dict[str, str] = field(default_factory=dict)
    status: str = "active"
    span_id_map: Dict[str, str] = field(default_factory=dict)
    last_span_id: str = ""

    def step_forward(self) -> Optional[Dict[str, Any]]:
        if self.cursor >= len(self.graph):
            return None
        node = self.graph[self.cursor]
        self.cursor += 1
        return node

    def step_back(self) -> List[Dict[str, Any]]:
        """Move cursor back, return downstream nodes to tear off."""
        if self.cursor <= 0:
            return []
        self.cursor -= 1
        torn = self.graph[self.cursor:]
        return torn

    def get_node(self, node_id: str) -> Optional[Dict[str, Any]]:
        for node in self.graph:
            if node["node_id"] == node_id:
                return node
        return None

    def get_node_index(self, node_id: str) -> int:
        for i, node in enumerate(self.graph):
            if node["node_id"] == node_id:
                return i
        return -1

    def tear_off_from(self, index: int) -> List[Dict[str, Any]]:
        """Return nodes from index onward (for span deletion)."""
        if index < 0 or index >= len(self.graph):
            return []
        torn = self.graph[index + 1:]
        return torn

    def to_state_dict(self) -> Dict[str, Any]:
        return {
            "session_id": self.session_id,
            "trace_id": self.trace_id,
            "cursor": self.cursor,
            "total_nodes": len(self.graph),
            "status": self.status,
            "current_node": self.graph[self.cursor] if self.cursor < len(self.graph) else None,
            "last_span_id": self.last_span_id,
            "last_node": self.graph[self.cursor - 1] if self.cursor > 0 else None,
        }

    def to_redis(self, client: Any) -> None:
        key = f"opik:debug:{self.session_id}"
        client.set(key, json.dumps(self.to_state_dict()))

        graph_key = f"opik:debug:{self.session_id}:graph"
        client.set(graph_key, json.dumps(self.graph))

    @staticmethod
    def graph_from_nodes(nodes: List[GraphNode]) -> List[Dict[str, Any]]:
        from dataclasses import asdict
        return [asdict(n) for n in nodes]
