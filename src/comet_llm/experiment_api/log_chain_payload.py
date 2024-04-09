import dataclasses
from typing import Dict, List, Optional

from ..types import JSONEncodable


@dataclasses.dataclass
class LogChainPayloadData:
    experiment_key: str
    chain_asset: Dict[str, JSONEncodable]
    workspace: Optional[str] = None
    project: Optional[str] = None
    parameters: Optional[List[Dict[str, JSONEncodable]]] = None
    metrics: Optional[List[Dict[str, JSONEncodable]]] = None
    tags: Optional[List[str]] = None
    others: Optional[List[Dict[str, JSONEncodable]]] = None
