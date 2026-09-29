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
    strict: bool = False,
) -> List[T]:
    """
    Args:
        strict: When True, a page that drops any record raises instead of
            returning a short result. Callers that turn a short read into
            deletions (the migrate paths) must fail loudly rather than replay
            missing records as deletions; search paths, where the caller can see
            the warning, keep the default.
    """
    result: List[T] = []
    # Per-page page size, adaptively halved on size-correlated failures and
    # held at the shrunk value for the rest of the read (a backend that
    # couldn't serve N items is unlikely to serve N again later).
    batch_size = max_endpoint_batch_size
    dropped_records_total = 0
    # The backend pages with `id < :last_received_id`, so the cursor has to be
    # the last id the backend *sent*, not the last one that parsed: a page that
    # ends with unreadable records would otherwise be served again.
    cursor: Optional[str] = None
    while True:
        if max_results is None:
            current_batch_size = batch_size
        else:
            amount_left = max_results - len(result)
            current_batch_size = min(amount_left, batch_size)

        if current_batch_size <= 0:
            # no more data to request
            break

        last_retrieved_id = cursor
        dropped_records: Dict[str, int] = {"count": 0}
        page_last_id: Dict[str, Optional[str]] = {"id": None}
        try:
            results_stream = read_source(current_batch_size, last_retrieved_id)
            parsed_items = read_and_parse_stream(
                stream=results_stream,
                item_class=parsed_item_class,
                dropped_records_out=dropped_records,
                last_id_out=page_last_id,
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
        cursor_before_page = cursor
        if page_last_id["id"] is not None:
            cursor = page_last_id["id"]
        elif len(result) > 0:
            # No line on this page decoded to an object with an id; fall back to
            # the last record that parsed so the read still advances.
            cursor = result[-1].id  # type: ignore

        # Records the backend sent but that failed to parse were still part of
        # this page, so they count towards it being full. Leaving them out makes
        # a single unreadable record look like the end of the stream and abandons
        # every remaining page.
        records_received = len(parsed_items) + dropped_records["count"]

        if strict and dropped_records["count"] > 0:
            # A single unreadable record leaves a hole just as wide as a page
            # that failed entirely: the caller cannot tell the two apart, and
            # the migrate paths would turn the hole into a deletion.
            if not parsed_items:
                raise ValueError(
                    f"All {dropped_records['count']} record(s) of the current page "
                    f"could not be parsed as {parsed_item_class.__name__}, so the "
                    f"result would be incomplete; refusing to return it."
                )
            raise ValueError(
                f"{dropped_records['count']} of the {records_received} record(s) "
                f"of the current page could not be parsed as "
                f"{parsed_item_class.__name__}, so the result would be "
                f"incomplete; refusing to return it."
            )

        if records_received >= current_batch_size and not parsed_items:
            if cursor == cursor_before_page:
                # Neither the result nor the cursor moved, so the next request
                # would ask for exactly the same page. Stop rather than loop.
                LOGGER.warning(
                    "Stream read stopped early: all %d record(s) of the current "
                    "page could not be parsed as %s, so pagination could not "
                    "advance.",
                    dropped_records["count"],
                    parsed_item_class.__name__,
                )
                break
            # No usable record on this page, but the backend's position moved, so
            # the next page is a different one. Keep reading.
            LOGGER.warning(
                "Page of %d unreadable record(s) as %s; continuing after the last "
                "id the backend sent.",
                dropped_records["count"],
                parsed_item_class.__name__,
            )

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
    last_id_out: Optional[Dict[str, Optional[str]]] = None,
) -> List[T]:
    """Parse an NDJSON stream into ``item_class`` instances.

    Records that cannot be decoded or validated are logged and skipped. When
    ``dropped_records_out`` is given, its ``"count"`` key is set to how many
    were skipped, which is what a paginating caller needs to tell a short page
    (end of data) apart from a full page with unreadable records. When
    ``last_id_out`` is given, its ``"id"`` key is set to the ``id`` of the last
    line that decoded to an object, even if that record failed validation: a
    paginating caller needs the backend's own position, not the last record it
    managed to use.
    """
    result: List[T] = []
    dropped_records = 0
    last_id: Optional[str] = None

    # last record in chunk may be incomplete, we will use this buffer to concatenate strings
    buffer = b""

    for chunk in stream:
        buffer += chunk
        lines = buffer.split(b"\n")

        # last record in chunk may be incomplete
        for line in lines[:-1]:
            item, raw_id = _parse_stream_line(line=line, item_class=item_class)
            if raw_id is not None:
                last_id = raw_id
            if item is None:
                dropped_records += 1
            else:
                result.append(item)

                if nb_samples is not None and len(result) == nb_samples:
                    _report_dropped_records(dropped_records_out, dropped_records)
                    _report_last_id(last_id_out, last_id)
                    return result

        # Keep the last potentially incomplete line in buffer
        buffer = lines[-1]

    # Process any remaining data in the buffer after the stream ends
    if buffer:
        item, raw_id = _parse_stream_line(line=buffer, item_class=item_class)
        if raw_id is not None:
            last_id = raw_id
        if item is None:
            dropped_records += 1
        else:
            result.append(item)

    _report_dropped_records(dropped_records_out, dropped_records)
    _report_last_id(last_id_out, last_id)
    return result


def _report_dropped_records(
    dropped_records_out: Optional[Dict[str, int]],
    dropped_records: int,
) -> None:
    if dropped_records_out is not None:
        dropped_records_out["count"] = dropped_records


def _report_last_id(
    last_id_out: Optional[Dict[str, Optional[str]]],
    last_id: Optional[str],
) -> None:
    if last_id_out is not None:
        last_id_out["id"] = last_id


def _parse_stream_line(
    line: bytes,
    item_class: Type[T],
) -> Tuple[Optional[T], Optional[str]]:
    """Parse one NDJSON line.

    Returns the parsed item (or None when the record is unusable) together with
    the ``id`` the record carried, which is reported even when validation of the
    rest of the record failed.
    """
    raw_id: Optional[str] = None
    try:
        item_dict = json.loads(line.decode("utf-8"))
        if isinstance(item_dict, dict):
            raw_id = item_dict.get("id")
        item_obj = item_class(**item_dict)
        return item_obj, raw_id

    except json.JSONDecodeError as e:
        LOGGER.error(f"Error decoding {item_class.__name__}, reason: {e}")
    except (TypeError, ValueError) as e:
        LOGGER.error(f"Error parsing {item_class.__name__}, reason: {e}")
    except Exception as e:
        LOGGER.error(f"Error decoding or parsing {item_class.__name__}, reason: {e}")

    return None, raw_id
