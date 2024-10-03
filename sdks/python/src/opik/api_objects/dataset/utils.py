import json
import hashlib
from . import dataset_item


def compute_content_hash(item: dataset_item.DatasetItem) -> str:
    content = {
        "input": item.input,
        "expected_output": item.expected_output,
        "metadata": item.metadata,
    }

    # Convert the dictionary to a JSON string with sorted keys for consistency
    json_string = json.dumps(content, sort_keys=True)

    # Compute the SHA256 hash of the JSON string
    hash_object = hashlib.sha256(json_string.encode())

    # Return the hexadecimal representation of the hash
    return hash_object.hexdigest()
