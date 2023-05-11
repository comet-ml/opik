from typing import Any, Dict, Optional

from .types import JSONEncodable, Timestamp


def call_data_to_dict(
    prompt: JSONEncodable,
    output: JSONEncodable,
    metadata: Optional[Dict[str, Any]],
    prompt_template: Optional[JSONEncodable],
    prompt_variables: Optional[JSONEncodable],
    timestamp: Timestamp,
):
    pass
