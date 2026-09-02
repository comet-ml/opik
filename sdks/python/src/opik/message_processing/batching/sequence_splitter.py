import logging
import math
from typing import Any, Iterable, Iterator, List, Optional, Sequence, TypeVar
import opik.jsonable_encoder as jsonable_encoder

T = TypeVar("T")

LOGGER = logging.getLogger(__name__)


def _get_expected_payload_size_MB(item: T) -> float:
    encoded_for_json = jsonable_encoder.encode(item)
    size = _get_json_size(encoded_for_json)
    return size / (1024 * 1024)


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


def stream_into_batches(
    items: Iterable[T],
    max_payload_size_MB: Optional[float] = None,
    max_length: Optional[int] = None,
) -> Iterator[List[T]]:
    """Yield batches as items arrive, holding one batch at a time.

    The streaming half of :func:`split_into_batches`, which is now a thin wrapper
    around it - same batch boundaries, same order, one implementation of the size
    accounting.

    What this buys over the eager version is a ceiling: a caller uploading a
    million items pays for one batch of memory instead of a million items' worth,
    and can hand in a generator that reads from a file or a database cursor. It
    takes an `Iterable` rather than a `Sequence` for that reason, and consumes it
    exactly once.

    `max_length` of `None` means "no limit on the count" here. The eager version
    said `len(items)`, which no iterable can answer and which meant the same
    thing: a batch can never hold more items than were passed in.
    """
    assert (max_payload_size_MB is not None) or (max_length is not None), (
        "At least one limitation must be set for splitting"
    )

    length_limit: float = math.inf if max_length is None else max_length
    size_limit: float = math.inf if max_payload_size_MB is None else max_payload_size_MB

    current_batch: List[T] = []
    current_batch_size_MB: float = 0.0

    for item in items:
        item_size_MB = _get_expected_payload_size_MB(item)

        if item_size_MB >= size_limit:
            # Ahead of whatever is still accumulating, which is what the eager
            # version did by appending to the result list directly. An oversized
            # item cannot join a batch, and holding it back to preserve input
            # order would mean keeping two batches alive.
            yield [item]
            continue

        batch_is_already_full = len(current_batch) == length_limit
        batch_will_exceed_memory_limit_after_adding = (
            current_batch_size_MB + item_size_MB > size_limit
        )

        if batch_is_already_full or batch_will_exceed_memory_limit_after_adding:
            yield current_batch
            current_batch = [item]
            current_batch_size_MB = item_size_MB
        else:
            current_batch.append(item)
            current_batch_size_MB += item_size_MB

    if len(current_batch) > 0:
        yield current_batch


def split_into_batches(
    items: Sequence[T],
    max_payload_size_MB: Optional[float] = None,
    max_length: Optional[int] = None,
) -> List[List[T]]:
    return list(
        stream_into_batches(
            items,
            max_payload_size_MB=max_payload_size_MB,
            max_length=max_length,
        )
    )
