import json
import logging
from typing import (
    Any,
    Callable,
    Iterable,
    List,
    NamedTuple,
    Optional,
    Set,
    Tuple,
    Type,
    TypeVar,
)

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


class StreamReadResult(List[T]):
    """The items read, plus what was lost on the way.

    Still a plain list for existing callers. ``dropped_records`` counts records the
    backend sent that could not be parsed, and ``truncated`` is True when the read
    stopped because the pagination cursor could not move on, so the backend may
    have had more.
    """

    dropped_records: int = 0
    truncated: bool = False


def read_and_parse_full_stream(
    read_source: Callable[[int, Optional[str]], Iterable[bytes]],
    parsed_item_class: Type[T],
    max_results: Optional[int],
    max_endpoint_batch_size: int = MAX_ENDPOINT_BATCH_SIZE,
) -> StreamReadResult[T]:
    result: StreamReadResult[T] = StreamReadResult()
    # Per-page page size, adaptively halved on size-correlated failures and
    # held at the shrunk value for the rest of the read (a backend that
    # couldn't serve N items is unlikely to serve N again later).
    batch_size = max_endpoint_batch_size
    # Each request pages from the id of the last record the backend sent, parsed or
    # not. Paging only continues while that cursor reaches a value not requested
    # before, see the guard below.
    cursor: Optional[str] = None
    requested_cursors: Set[Optional[str]] = set()
    while True:
        if max_results is None:
            current_batch_size = batch_size
        else:
            amount_left = max_results - len(result)
            current_batch_size = min(amount_left, batch_size)

        if current_batch_size <= 0:
            # no more data to request
            break

        # A cursor that was already requested would re-read a page we have, forever,
        # appending duplicates. That happens when records come back without an id,
        # and it can alternate ('004' -> None -> '004'), so compare against every
        # cursor used so far, not just the last one. Checked before the request so
        # none of that is fetched.
        if cursor in requested_cursors:
            result.truncated = True
            break

        try:
            results_stream = read_source(current_batch_size, cursor)
            page = _read_and_parse_stream(
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

        # Only after a page actually came back: a size-correlated retry deliberately
        # re-uses the same cursor, and must not look like a stalled one.
        requested_cursors.add(cursor)
        result.extend(page.items)
        result.dropped_records += page.received_records - len(page.items)
        cursor = page.last_record_id

        # Compare against what the backend sent, not what parsed. A record the
        # client cannot parse is dropped by `_parse_stream_line`, and counting the
        # survivors made a short page look like the last one: a single unparseable
        # record ended the read and silently returned a fraction of the results.
        if current_batch_size > page.received_records:
            break

    if result.dropped_records or result.truncated:
        LOGGER.warning(
            "Incomplete read of %s: returning %d item(s), %d record(s) could not be "
            "parsed%s.",
            parsed_item_class.__name__,
            len(result),
            result.dropped_records,
            (
                ", and pagination stopped because the cursor did not advance"
                if result.truncated
                else ""
            ),
        )

    return result


def read_and_parse_stream(
    stream: Iterable[bytes],
    item_class: Type[T],
    nb_samples: Optional[int] = None,
) -> List[T]:
    return _read_and_parse_stream(stream, item_class, nb_samples).items


class _ParsedPage(NamedTuple):
    items: List[Any]
    # Records the backend sent, including ones that failed to parse. Only this says
    # whether the backend had more to send.
    received_records: int
    # Id of the last record sent, read from the raw record so that a page ending in
    # an unparseable record still moves the cursor past it.
    last_record_id: Optional[str]


def _read_and_parse_stream(
    stream: Iterable[bytes],
    item_class: Type[T],
    nb_samples: Optional[int] = None,
) -> _ParsedPage:
    result: List[T] = []
    received_records = 0
    last_record_id: Optional[str] = None

    def handle(line: bytes) -> None:
        nonlocal received_records, last_record_id
        received_records += 1
        item_dict = _decode_stream_line(line, item_class)
        if item_dict is None:
            return
        last_record_id = item_dict.get("id") if isinstance(item_dict, dict) else None
        item = _build_item(item_dict, item_class)
        if item is not None:
            result.append(item)

    # last record in chunk may be incomplete, we will use this buffer to concatenate strings
    buffer = b""

    for chunk in stream:
        buffer += chunk
        lines = buffer.split(b"\n")

        # last record in chunk may be incomplete
        for line in lines[:-1]:
            if not line.strip():
                continue
            handle(line)
            if nb_samples is not None and len(result) == nb_samples:
                return _ParsedPage(result, received_records, last_record_id)

        # Keep the last potentially incomplete line in buffer
        buffer = lines[-1]

    # Process any remaining data in the buffer after the stream ends
    if buffer.strip():
        handle(buffer)

    return _ParsedPage(result, received_records, last_record_id)


def _parse_stream_line(
    line: bytes,
    item_class: Type[T],
) -> Optional[T]:
    item_dict = _decode_stream_line(line, item_class)
    return None if item_dict is None else _build_item(item_dict, item_class)


def _decode_stream_line(line: bytes, item_class: Type[T]) -> Any:
    try:
        return json.loads(line.decode("utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        LOGGER.error(f"Error decoding {item_class.__name__}, reason: {e}")
        return None


def _build_item(item_dict: Any, item_class: Type[T]) -> Optional[T]:
    try:
        return item_class(**item_dict)
    except (TypeError, ValueError) as e:
        LOGGER.error(f"Error parsing {item_class.__name__}, reason: {e}")
    except Exception as e:
        LOGGER.error(f"Error decoding or parsing {item_class.__name__}, reason: {e}")
    return None
