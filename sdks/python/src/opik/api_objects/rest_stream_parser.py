import json
import logging
from typing import Callable, Iterable, Type, List, Optional, Tuple, TypeVar

import httpx

LOGGER = logging.getLogger(__name__)


# this is the constant for the maximum number of objects sent from the backend side
MAX_ENDPOINT_BATCH_SIZE = 2_000

# Floor for the adaptive page-size shrink below. Once a page this small still
# fails with a connection/timeout error, shrinking further won't help (the
# failure isn't size-correlated), so we re-raise instead of looping forever.
MIN_ENDPOINT_BATCH_SIZE = 50

# Connection/timeout errors whose likelihood scales with response size. A
# large page drives a heavy backend read (see the migrate cascade, OPIK-7152:
# a 2.93M rows/s ClickHouse SELECT pushed container RSS to ~3 GiB via
# native/off-heap read buffers and the pod was OOM-killed mid-stream), which
# drops the socket and surfaces to httpx as RemoteProtocolError ("incomplete
# chunked read"); an overlong read shows up as ReadTimeout; a refused connect
# as ConnectError. Halving the page and retrying the same cursor lets a
# too-large request succeed on a smaller one. Other errors (auth, 4xx, parse)
# are NOT in this set and propagate as before.
_SIZE_CORRELATED_ERRORS: Tuple[Type[Exception], ...] = (
    httpx.RemoteProtocolError,
    httpx.ReadTimeout,
    httpx.ConnectError,
)


T = TypeVar("T")


def _default_cursor_extractor(item: T) -> Optional[str]:
    # Most endpoints paginate by the item's `id` field. Endpoints whose cursor
    # is a different column (e.g. the trace-threads endpoint paginates by
    # `thread_model_id`) pass their own extractor instead.
    return item.id  # type: ignore


def read_and_parse_full_stream(
    read_source: Callable[[int, Optional[str]], Iterable[bytes]],
    parsed_item_class: Type[T],
    max_results: Optional[int],
    max_endpoint_batch_size: int = MAX_ENDPOINT_BATCH_SIZE,
    cursor_extractor: Optional[Callable[[T], Optional[str]]] = None,
) -> List[T]:
    result: List[T] = []
    extract_cursor = (
        cursor_extractor if cursor_extractor is not None else _default_cursor_extractor
    )
    # Per-page page size, adaptively halved on size-correlated failures and
    # held at the shrunk value for the rest of the read (a backend that
    # couldn't serve N items is unlikely to serve N again later).
    batch_size = max_endpoint_batch_size
    while True:
        if max_results is None:
            current_batch_size = batch_size
        else:
            amount_left = max_results - len(result)
            current_batch_size = min(amount_left, batch_size)

        if current_batch_size <= 0:
            # no more data to request
            break

        last_retrieved_id = extract_cursor(result[-1]) if len(result) > 0 else None
        try:
            results_stream = read_source(current_batch_size, last_retrieved_id)
            parsed_items = read_and_parse_stream(
                stream=results_stream, item_class=parsed_item_class
            )
        except _SIZE_CORRELATED_ERRORS as exc:
            if batch_size <= MIN_ENDPOINT_BATCH_SIZE:
                # Already at the floor — the failure isn't size-correlated.
                raise
            batch_size = max(batch_size // 2, MIN_ENDPOINT_BATCH_SIZE)
            LOGGER.warning(
                "Stream read failed with %s; halving page size to %d and "
                "retrying from the same cursor.",
                type(exc).__name__,
                batch_size,
            )
            continue

        result.extend(parsed_items)

        if current_batch_size > len(parsed_items):
            break

        # Stop when the backend gives us no cursor to advance past (a NULL
        # cursor, e.g. a NULL thread_model_id from the LEFT JOIN on
        # trace_threads_final), or repeats the previous one, so we never loop
        # forever or re-request the same page. This is a hard stop, not a
        # signal that the stream is exhausted: a full page ending without an
        # advancing cursor means the returned list may be truncated, so log it
        # instead of stopping silently.
        next_cursor = extract_cursor(result[-1]) if len(result) > 0 else None
        if next_cursor is None or next_cursor == last_retrieved_id:
            if len(parsed_items) == current_batch_size:
                reason = (
                    "the last item carries no cursor"
                    if next_cursor is None
                    else "the cursor repeats the previous page's cursor"
                )
                LOGGER.warning(
                    "Stopping paginated stream after a full page (%s); "
                    "the returned list may be truncated.",
                    reason,
                )
            break

    return result


def read_and_parse_stream(
    stream: Iterable[bytes],
    item_class: Type[T],
    nb_samples: Optional[int] = None,
) -> List[T]:
    result: List[T] = []

    # last record in chunk may be incomplete, we will use this buffer to concatenate strings
    buffer = b""

    for chunk in stream:
        buffer += chunk
        lines = buffer.split(b"\n")

        # last record in chunk may be incomplete
        for line in lines[:-1]:
            item = _parse_stream_line(line=line, item_class=item_class)
            if item is not None:
                result.append(item)

                if nb_samples is not None and len(result) == nb_samples:
                    return result

        # Keep the last potentially incomplete line in buffer
        buffer = lines[-1]

    # Process any remaining data in the buffer after the stream ends
    if buffer:
        item = _parse_stream_line(line=buffer, item_class=item_class)
        if item is not None:
            result.append(item)

    return result


def _parse_stream_line(
    line: bytes,
    item_class: Type[T],
) -> Optional[T]:
    try:
        item_dict = json.loads(line.decode("utf-8"))
        item_obj = item_class(**item_dict)
        return item_obj

    except json.JSONDecodeError as e:
        LOGGER.error(f"Error decoding {item_class.__name__}, reason: {e}")
    except (TypeError, ValueError) as e:
        LOGGER.error(f"Error parsing {item_class.__name__}, reason: {e}")
    except Exception as e:
        LOGGER.error(f"Error decoding or parsing {item_class.__name__}, reason: {e}")

    return None
