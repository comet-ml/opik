import json
from typing import List, Dict, Any, Optional, TypeVar
from opik.rest_api.types import SpanWrite, TraceWrite, DatasetItemWrite, ExperimentItem
from opik import jsonable_encoder

RestItem = TypeVar(
    "RestItem",
    SpanWrite,
    TraceWrite,
    DatasetItemWrite,
    ExperimentItem,
)


def _get_expected_payload_size(payload: Dict[str, Any]) -> float:
    encoded_for_json = jsonable_encoder.jsonable_encoder(payload)
    json_str = json.dumps(encoded_for_json)
    return len(json_str.encode("utf-8")) / (1024 * 1024)


def split_list_into_batches(
    rest_items: List[RestItem],
    max_memory_size_MB: Optional[float] = None,
    max_length: Optional[int] = None,
) -> List[List[RestItem]]:
    assert (max_memory_size_MB is not None) or (
        max_length is not None
    ), "At least one limitation must be set for splitting"

    if max_length is None:
        max_length = len(rest_items)

    if max_memory_size_MB is None:
        max_memory_size_MB = float("inf")

    batches: List[List[RestItem]] = []
    current_batch: List[RestItem] = []
    current_batch_size_MB: float = 0.0

    for item in rest_items:
        item_size = _get_expected_payload_size(item.__dict__)

        if item_size >= max_memory_size_MB:
            batches.append([item])
            continue

        if (
            len(current_batch) >= max_length
            or current_batch_size_MB + item_size > max_memory_size_MB
        ):
            batches.append(current_batch)
            current_batch = [item]
            current_batch_size_MB = item_size
        else:
            current_batch.append(item)
            current_batch_size_MB += item_size

    if current_batch:
        batches.append(current_batch)

    return batches
