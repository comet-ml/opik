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
    cursor_field: str = "id",
    strict: bool = False,
) -> List[T]:
    """
    Args:
        cursor_field: The record field the backend pages by. Most endpoints
            page by ``id``; the trace-threads endpoint pages by
            ``thread_model_id``. The cursor is that field's value on the last
            line the backend sent, whether or not the record parsed, so a page
            ending in unreadable records is never served twice.
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
    # The backend pages by the cursor field, so the cursor has to be that
    # field's value on the last line the backend *sent*, not the last record
    # that parsed: a page ending in unreadable records would otherwise be
    # served again.
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
        page_cursor_known: Dict[str, bool] = {"known": False}
        try:
            results_stream = read_source(current_batch_size, last_retrieved_id)
            parsed_items = read_and_parse_stream(
                stream=results_stream,
                item_class=parsed_item_class,
                dropped_records_out=dropped_records,
                last_id_out=page_last_id,
                cursor_known_out=page_cursor_known,
                cursor_field=cursor_field,
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
        if page_cursor_known["known"]:
            # A decoded line carried the cursor field, so the backend
            # position is known even when its value is NULL: keep None as
            # the cursor and let the stop checks below end the read. NULL
            # rows sort last, so nothing past them is lost, and the next
            # request would only re-fetch the same NULL rows.
            cursor = page_last_id["id"]
        elif parsed_items:
            # No decoded line carried a cursor value, so fall back to
            # the last parsed record: the read still advances instead of
            # stopping at the first page. The backend position above always
            # wins when it is reported.
            cursor = getattr(parsed_items[-1], cursor_field, None)

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

        # A missing (e.g. NULL thread_model_id) or repeated cursor would re-request the
        # same page: stop, and warn on a full page since the list may be truncated.
        if cursor is None or cursor == last_retrieved_id:
            if len(parsed_items) == current_batch_size:
                reason = (
                    "the last item carries no cursor"
                    if cursor is None
                    else "the cursor repeats the previous page's cursor"
                )
                LOGGER.warning(
                    "Stopping paginated stream after a full page (%s); "
                    "the returned list may be truncated.",
                    reason,
                )
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
    cursor_known_out: Optional[Dict[str, bool]] = None,
    cursor_field: str = "id",
) -> List[T]:
    """Parse an NDJSON stream into ``item_class`` instances.

    Records that cannot be decoded or validated are logged and skipped. When
    ``dropped_records_out`` is given, its ``"count"`` key is set to how many
    were skipped, which is what a paginating caller needs to tell a short page
    (end of data) apart from a full page with unreadable records. When
    ``last_id_out`` is given, its ``"id"`` key is set to the ``cursor_field``
    value of the last line that decoded to an object, even if that record
    failed validation: a paginating caller needs the backend's own position,
    not the last record it managed to use. When ``cursor_known_out`` is given,
    its ``"known"`` key says whether any line decoded to an object at all, so
    the caller can tell an explicit NULL cursor apart from no cursor
    reported: only the former must stop the read.
    """
    result: List[T] = []
    dropped_records = 0
    last_cursor: Optional[str] = None
    last_cursor_known = False

    # last record in chunk may be incomplete, we will use this buffer to concatenate strings
    buffer = b""

    for chunk in stream:
        buffer += chunk
        lines = buffer.split(b"\n")

        # last record in chunk may be incomplete
        for line in lines[:-1]:
            item, line_cursor, line_cursor_known = _parse_stream_line(
                line=line, item_class=item_class, cursor_field=cursor_field
            )
            if line_cursor_known:
                last_cursor = line_cursor
                last_cursor_known = True
            if item is None:
                dropped_records += 1
            else:
                result.append(item)

                if nb_samples is not None and len(result) == nb_samples:
                    _report_dropped_records(dropped_records_out, dropped_records)
                    _report_last_id(last_id_out, last_cursor)
                    _report_cursor_known(cursor_known_out, last_cursor_known)
                    return result

        # Keep the last potentially incomplete line in buffer
        buffer = lines[-1]

    # Process any remaining data in the buffer after the stream ends
    if buffer:
        item, line_cursor, line_cursor_known = _parse_stream_line(
            line=buffer, item_class=item_class, cursor_field=cursor_field
        )
        if line_cursor_known:
            last_cursor = line_cursor
            last_cursor_known = True
        if item is None:
            dropped_records += 1
        else:
            result.append(item)

    _report_dropped_records(dropped_records_out, dropped_records)
    _report_last_id(last_id_out, last_cursor)
    _report_cursor_known(cursor_known_out, last_cursor_known)
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


def _report_cursor_known(
    cursor_known_out: Optional[Dict[str, bool]],
    cursor_known: bool,
) -> None:
    if cursor_known_out is not None:
        cursor_known_out["known"] = cursor_known


def _parse_stream_line(
    line: bytes,
    item_class: Type[T],
    cursor_field: str = "id",
) -> Tuple[Optional[T], Optional[str], bool]:
    """Parse one NDJSON line.

    Returns the parsed item (or None when the record is unusable), the
    ``cursor_field`` value the record carried, and whether the line decoded
    to an object at all. The value is reported even when validation of the
    rest of the record failed, and is None when the field was NULL or absent;
    the flag is what tells those cases apart from a line that never decoded.
    """
    cursor_value: Optional[str] = None
    cursor_known = False
    try:
        item_dict = json.loads(line.decode("utf-8"))
        if isinstance(item_dict, dict):
            cursor_value = item_dict.get(cursor_field)
            cursor_known = True
        item_obj = item_class(**item_dict)
        return item_obj, cursor_value, cursor_known

    except json.JSONDecodeError as e:
        LOGGER.error(f"Error decoding {item_class.__name__}, reason: {e}")
    except (TypeError, ValueError) as e:
        LOGGER.error(f"Error parsing {item_class.__name__}, reason: {e}")
    except Exception as e:
        LOGGER.error(f"Error decoding or parsing {item_class.__name__}, reason: {e}")

    return None, cursor_value, cursor_known
