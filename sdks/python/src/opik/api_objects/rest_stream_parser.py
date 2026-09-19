import json
import logging
from typing import Callable, Dict, Iterable, Type, List, Optional, Tuple, TypeVar

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
    dropped_records_total = 0
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
        dropped_records: Dict[str, int] = {"count": 0}
        try:
            results_stream = read_source(current_batch_size, last_retrieved_id)
            parsed_items = read_and_parse_stream(
                stream=results_stream,
                item_class=parsed_item_class,
                dropped_records_out=dropped_records,
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
        dropped_records_total += dropped_records["count"]

        # Records the backend sent but that failed to parse were still part of
        # this page, so they count towards it being full. Leaving them out makes
        # a single unreadable record look like the end of the stream and abandons
        # every remaining page.
        records_received = len(parsed_items) + dropped_records["count"]

        if records_received >= current_batch_size and not parsed_items:
            # A page of nothing but unreadable records advances neither the
            # result nor the cursor, so the next request would ask for exactly
            # the same page. Stop rather than loop.
            LOGGER.warning(
                "Stream read stopped early: all %d record(s) of the current "
                "page could not be parsed as %s, so pagination could not "
                "advance.",
                dropped_records["count"],
                parsed_item_class.__name__,
            )
            break

        if current_batch_size > records_received:
            break

    if dropped_records_total:
        LOGGER.warning(
            "Stream read of %s finished with %d record(s) dropped because they "
            "could not be parsed; the result is incomplete.",
            parsed_item_class.__name__,
            dropped_records_total,
        )

    return result


def read_and_parse_stream(
    stream: Iterable[bytes],
    item_class: Type[T],
    nb_samples: Optional[int] = None,
    dropped_records_out: Optional[Dict[str, int]] = None,
) -> List[T]:
    """Parse an NDJSON stream into ``item_class`` instances.

    Records that cannot be decoded or validated are logged and skipped. When
    ``dropped_records_out`` is given, its ``"count"`` key is set to how many
    were skipped, which is what a paginating caller needs to tell a short page
    (end of data) apart from a full page with unreadable records.
    """
    result: List[T] = []
    dropped_records = 0

    # last record in chunk may be incomplete, we will use this buffer to concatenate strings
    buffer = b""

    for chunk in stream:
        buffer += chunk
        lines = buffer.split(b"\n")

        # last record in chunk may be incomplete
        for line in lines[:-1]:
            item = _parse_stream_line(line=line, item_class=item_class)
            if item is None:
                dropped_records += 1
            else:
                result.append(item)

                if nb_samples is not None and len(result) == nb_samples:
                    _report_dropped_records(dropped_records_out, dropped_records)
                    return result

        # Keep the last potentially incomplete line in buffer
        buffer = lines[-1]

    # Process any remaining data in the buffer after the stream ends
    if buffer:
        item = _parse_stream_line(line=buffer, item_class=item_class)
        if item is None:
            dropped_records += 1
        else:
            result.append(item)

    _report_dropped_records(dropped_records_out, dropped_records)
    return result


def _report_dropped_records(
    dropped_records_out: Optional[Dict[str, int]],
    dropped_records: int,
) -> None:
    if dropped_records_out is not None:
        dropped_records_out["count"] = dropped_records


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
