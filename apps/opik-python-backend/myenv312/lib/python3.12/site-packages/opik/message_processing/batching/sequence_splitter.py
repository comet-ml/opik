import json
from typing import List, Optional, TypeVar, Sequence
from opik import jsonable_encoder

T = TypeVar("T")


def _get_expected_payload_size_MB(item: T) -> float:
    encoded_for_json = jsonable_encoder.jsonable_encoder(item)
    json_str = json.dumps(encoded_for_json)
    return len(json_str.encode("utf-8")) / (1024 * 1024)


def split_into_batches(
    items: Sequence[T],
    max_payload_size_MB: Optional[float] = None,
    max_length: Optional[int] = None,
) -> List[List[T]]:
    assert (max_payload_size_MB is not None) or (
        max_length is not None
    ), "At least one limitation must be set for splitting"

    if max_length is None:
        max_length = len(items)

    if max_payload_size_MB is None:
        max_payload_size_MB = float("inf")

    batches: List[List[T]] = []
    current_batch: List[T] = []
    current_batch_size_MB: float = 0.0

    for item in items:
        item_size_MB = (
            0.0 if max_payload_size_MB is None else _get_expected_payload_size_MB(item)
        )

        if item_size_MB >= max_payload_size_MB:
            batches.append([item])
            continue

        batch_is_already_full = len(current_batch) == max_length
        batch_will_exceed_memory_limit_after_adding = (
            current_batch_size_MB + item_size_MB > max_payload_size_MB
        )

        if batch_is_already_full or batch_will_exceed_memory_limit_after_adding:
            batches.append(current_batch)
            current_batch = [item]
            current_batch_size_MB = item_size_MB
        else:
            current_batch.append(item)
            current_batch_size_MB += item_size_MB

    if len(current_batch) > 0:
        batches.append(current_batch)

    return batches
