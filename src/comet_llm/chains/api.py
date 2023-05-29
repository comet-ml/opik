from typing import Dict, Union, Optional
from ..types import JSONEncodable

def start_chain(
    inputs: Dict[str, JSONEncodable],
    api_key: Optional[str] = None,
    workspace: Optional[str] = None,
    project_name: Optional[str] = None,
    metadata: Optional[Dict[str, Dict[str, JSONEncodable]]] = None,
) -> None:
    pass

def end_chain(
    outputs: Dict[str, JSONEncodable],
    metadata: Optional[Dict[str, JSONEncodable]] = None,
) -> None:
    pass