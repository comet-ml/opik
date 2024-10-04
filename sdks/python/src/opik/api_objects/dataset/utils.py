import json
import hashlib
from typing import Dict, Any, Union
from . import dataset_item


def compute_content_hash(item: Union[dataset_item.DatasetItem, Dict[str, Any]]) -> str:
    if isinstance(item, dataset_item.DatasetItem):
        content = {
            "input": item.input,
            "expected_output": item.expected_output,
            "metadata": item.metadata,
        }
    else:
        content = item

    # Convert the dictionary to a JSON string with sorted keys for consistency
    json_string = json.dumps(content, sort_keys=True)

    # Compute the SHA256 hash of the JSON string
    hash_object = hashlib.sha256(json_string.encode())

    # Return the hexadecimal representation of the hash
    return hash_object.hexdigest()
