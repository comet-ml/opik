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


def read_and_parse_full_stream(
    read_source: Callable[[int, Optional[str]], Iterable[bytes]],
    parsed_item_class: Type[T],
    max_results: Optional[int],
    max_endpoint_batch_size: int = MAX_ENDPOINT_BATCH_SIZE,
) -> List[T]:
    result: List[T] = []
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

        last_retrieved_id = result[-1].id if len(result) > 0 else None  # type: ignore
        try:
            results_stream = read_source(current_batch_size, last_retrieved_id)
            parsed_items, received_records = _read_and_parse_stream(
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

        if not parsed_items and received_records > 0:
            # The page held records but none of them parsed, so there is no id to
            # page from: requesting again would re-read the same page forever.
            LOGGER.error(
                "None of the %d records on this page could be parsed as %s; "
                "stopping with %d item(s) read so far.",
                received_records,
                parsed_item_class.__name__,
                len(result),
            )
            break

        # Compare against what the backend sent, not what parsed. A record the
        # client cannot parse is dropped by `_parse_stream_line`, and counting the
        # survivors made a short page look like the last one: a single unparseable
        # record ended the read and silently returned a fraction of the results.
        if current_batch_size > received_records:
            break

    return result


def read_and_parse_stream(
    stream: Iterable[bytes],
    item_class: Type[T],
    nb_samples: Optional[int] = None,
) -> List[T]:
    items, _ = _read_and_parse_stream(stream, item_class, nb_samples)
    return items


def _read_and_parse_stream(
    stream: Iterable[bytes],
    item_class: Type[T],
    nb_samples: Optional[int] = None,
) -> Tuple[List[T], int]:
    """Parse the stream, also reporting how many records it held.

    Pagination needs the record count rather than the item count: the two differ
    whenever a record fails to parse, and only the former says whether the backend
    had more to send.
    """
    result: List[T] = []
    received_records = 0

    # last record in chunk may be incomplete, we will use this buffer to concatenate strings
    buffer = b""

    for chunk in stream:
        buffer += chunk
        lines = buffer.split(b"\n")

        # last record in chunk may be incomplete
        for line in lines[:-1]:
            if not line.strip():
                continue
            received_records += 1
            item = _parse_stream_line(line=line, item_class=item_class)
            if item is not None:
                result.append(item)

                if nb_samples is not None and len(result) == nb_samples:
                    return result, received_records

        # Keep the last potentially incomplete line in buffer
        buffer = lines[-1]

    # Process any remaining data in the buffer after the stream ends
    if buffer.strip():
        received_records += 1
        item = _parse_stream_line(line=buffer, item_class=item_class)
        if item is not None:
            result.append(item)

    return result, received_records


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
