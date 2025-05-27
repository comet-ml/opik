import logging
from typing import List, Optional, TypeVar, Sequence, Any
from opik import jsonable_encoder

T = TypeVar("T")

LOGGER = logging.getLogger(__name__)


def _get_expected_payload_size_MB(item: T) -> float:
    encoded_for_json = jsonable_encoder.encode(item)
    size = _get_json_size(encoded_for_json)
    return size / (1024 * 1024)


def _get_json_size(obj: Any) -> Any:
    """
    Compute the size of the resulting JSON without actually doing the JSON
    encoding, which is CPU and memory consuming. This assumes that we only
    receive basic Python objects, strings, booleans, numbers, list and dicts
    and that the object does not contain any cyclic reference.
    """
    try:
        if isinstance(obj, str):
            return len(obj.encode("utf-8")) + 2  # "str_content"
        elif isinstance(obj, (int, float)):
            return len(str(obj))
        elif isinstance(obj, type(None)):
            # null
            return 4
        elif isinstance(obj, dict):
            size = 2  # {obj}
            allowed_keys = set(obj.keys())
            for key, value in obj.items():
                if key in allowed_keys:
                    encoded_key = _get_json_size(key)
                    encoded_value = _get_json_size(value)
                    size += encoded_key + encoded_value + 1 + 1  # key:value and ,
            return size - 1  # Remove the last trailing comma
        elif isinstance(obj, list):
            size = 2  # [obj]
            for item in obj:
                size += _get_json_size(item) + 1  # ,
            return size - 1  # Remove the last trailing comma
        elif isinstance(obj, bool):
            return len(str(obj))
        else:
            LOGGER.debug(
                "Unexpected object seen during JSON size estimation %r", type(obj)
            )
            return len(str(obj))

    except Exception:
        LOGGER.debug("Failed to compute object size.", exc_info=True)
        # Return a value that will cause the span to be in its own batch to be on the safe side
        return float("inf")


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
