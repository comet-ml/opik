import logging
from typing import List, Optional, TypeVar, Sequence, Any
import opik.jsonable_encoder as jsonable_encoder

T = TypeVar("T")

LOGGER = logging.getLogger(__name__)

_BYTES_PER_MB = 1024 * 1024
# A batch is sent as a JSON list, so its brackets and the commas between its
# items ride along with the items themselves. Sizing only the items lets a
# batch packed right up to the limit cross it once serialized.
_JSON_LIST_BRACKETS_MB = 2 / _BYTES_PER_MB
_JSON_LIST_SEPARATOR_MB = 1 / _BYTES_PER_MB


def _get_expected_payload_size_MB(item: T) -> float:
    encoded_for_json = jsonable_encoder.encode(item)
    return get_encoded_payload_size_MB(encoded_for_json)


def get_encoded_payload_size_MB(encoded_for_json: Any) -> float:
    """Size an object that has already been through ``jsonable_encoder``.

    Split out so a caller that must guard the encoding step -- which ends in
    ``str(obj)`` and so runs arbitrary caller code -- can put its ``try`` around that
    call alone, instead of around this one too. Nothing here executes caller code, so
    anything raising below this line is a defect in the SDK and should say so.
    """
    return _get_json_size(encoded_for_json) / (1024 * 1024)


def get_payload_size_MB(item: T) -> float:
    """Estimate the JSON-serialized size of ``item`` in megabytes.

    Public wrapper around the internal size estimator, reused by span-truncation
    so the size measured for truncation matches the batching size estimate.
    """
    return _get_expected_payload_size_MB(item)


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
    assert (max_payload_size_MB is not None) or (max_length is not None), (
        "At least one limitation must be set for splitting"
    )

    if max_length is None:
        max_length = len(items)

    if max_payload_size_MB is None:
        max_payload_size_MB = float("inf")

    batches: List[List[T]] = []
    current_batch: List[T] = []
    current_batch_size_MB: float = _JSON_LIST_BRACKETS_MB

    for item in items:
        item_size_MB = _get_expected_payload_size_MB(item)

        if item_size_MB >= max_payload_size_MB:
            # Whatever is accumulating was read first and has to go out first.
            # Feedback scores upsert by (entity id, name) in the order they are sent,
            # so emitting this item ahead of them silently keeps the older value.
            if len(current_batch) > 0:
                batches.append(current_batch)
                current_batch = []
                current_batch_size_MB = _JSON_LIST_BRACKETS_MB
            batches.append([item])
            continue

        appended_size_MB = item_size_MB + (
            _JSON_LIST_SEPARATOR_MB if len(current_batch) > 0 else 0.0
        )

        batch_is_already_full = len(current_batch) == max_length
        # The first item of a batch always goes in: its brackets are unavoidable,
        # and an empty batch is worth less than a slightly oversized one.
        batch_will_exceed_memory_limit_after_adding = (
            len(current_batch) > 0
            and current_batch_size_MB + appended_size_MB > max_payload_size_MB
        )

        if batch_is_already_full or batch_will_exceed_memory_limit_after_adding:
            batches.append(current_batch)
            current_batch = [item]
            current_batch_size_MB = _JSON_LIST_BRACKETS_MB + item_size_MB
        else:
            current_batch.append(item)
            current_batch_size_MB += appended_size_MB

    if len(current_batch) > 0:
        batches.append(current_batch)

    return batches
