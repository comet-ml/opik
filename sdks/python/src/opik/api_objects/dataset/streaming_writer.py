"""Build dataset-item request bodies as rows arrive, instead of materialising the upload.

The path this replaced accumulated every item, split the list into batches, then serialised
and compressed each batch. That held the whole upload in memory and walked each item
several times over. This writer serialises a row once as it is added, feeds the bytes straight into
a zlib stream, and hands a finished request body to a callback when a threshold trips, so
what it holds is one in-flight body rather than the upload. What the caller keeps around
it -- deduplication digests, say -- is its own business.

Serialisation here is for the wire only. Content hashes are computed elsewhere, with the
standard library, so item identity never depends on which serialiser is in use.
"""

import dataclasses
import datetime
import decimal
import enum
import json
import logging
import pathlib
import time
import uuid
import zlib
from concurrent import futures
from typing import Any, Callable, Dict, Mapping, Optional, Set, overload

import pydantic

from ...rest_api.core.jsonable_encoder import jsonable_encoder

LOGGER = logging.getLogger(__name__)

try:
    import orjson
except ImportError:  # pragma: no cover
    # Not dead code: orjson is declared for every platform that has a wheel, and
    # deliberately not required on Windows ARM64 below Python 3.11, where none is
    # published. The standard library is the supported serialiser there, so this branch
    # is a real configuration rather than defensive coding -- do not delete it without
    # revisiting that marker in setup.py. Turning the serialiser off on purpose is a
    # separate thing, and is what `enable_orjson_serialization` is for.
    orjson = None  # type: ignore[assignment]

# gzip container rather than a raw deflate stream, matching what the server expects.
_GZIP_WBITS = 16 + zlib.MAX_WBITS

# Values the generated client accepted that a JSON serialiser rejects on its own, and that
# `jsonable_encoder` converts without looking inside anything: for these it is exact.
_FLEXIBLE_LEAVES = (
    bytes,
    enum.Enum,
    datetime.date,  # also covers datetime.datetime
    datetime.time,
    decimal.Decimal,
    uuid.UUID,
    pathlib.PurePath,
)


class ItemNotSerializableError(TypeError):
    """A dataset item could not be serialised to JSON.

    Raised explicitly rather than left to surface from whichever component happens to
    serialise first, so the failure names the item and does not depend on the wire
    serialiser in use.
    """


def encode_flexible(value: Any) -> Any:
    """One value the wire serialiser could not encode, in the form the client sent before.

    Called only for values a serialiser rejects, so ordinary JSON-native items never pay
    for the normalisation pass this restores.

    A value with an interior is handed back as its shell rather than encoded here, so the
    serialiser walks into it and comes back through this hook for each member. Letting
    `jsonable_encoder` encode the interior instead would apply its last resort to whatever
    it found there -- `vars(obj)` for an object with no JSON form, uploading it as a dict
    of its attributes rather than telling the caller it cannot be sent.
    """
    if isinstance(value, _FLEXIBLE_LEAVES):
        return jsonable_encoder(value)
    if isinstance(value, (set, frozenset, tuple)):
        return list(value)
    if isinstance(value, pydantic.BaseModel):
        # `model_dump`, not the deprecated `dict`, and in python mode so the members come
        # back through here rather than being encoded by pydantic on the way out.
        return value.model_dump(by_alias=True)
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return {
            field.name: getattr(value, field.name)
            for field in dataclasses.fields(value)
        }
    raise TypeError(f"Object of type {type(value).__name__} is not JSON serializable")


def _dumps_stdlib(value: Any) -> bytes:
    return json.dumps(value, default=encode_flexible).encode("utf-8")


def _dumps_orjson(value: Any) -> bytes:
    # Datetimes are passed through to the encoder above rather than serialised by orjson,
    # so the wire form of a date does not depend on which serialiser is in use.
    try:
        return orjson.dumps(
            value, default=encode_flexible, option=orjson.OPT_PASSTHROUGH_DATETIME
        )
    except TypeError:
        # Non-string mapping keys, which `json.dumps` coerces. Supporting them costs about
        # 2.5x per item when left on, so the rare item that has them pays for a retry
        # instead of every item paying for the option.
        try:
            return orjson.dumps(
                value,
                default=encode_flexible,
                option=orjson.OPT_PASSTHROUGH_DATETIME | orjson.OPT_NON_STR_KEYS,
            )
        except TypeError:
            # Integers outside 64 bits are the known case: the standard library writes
            # them, orjson refuses them at any option. Which serialiser is in use must not
            # decide whether an item can be uploaded, so the slower one finishes the job.
            return _dumps_stdlib(value)


def select_dumps(use_orjson: bool) -> Callable[[Any], bytes]:
    """Pick the wire serialiser. Falls back to the standard library when orjson is absent."""
    if use_orjson and orjson is not None:
        return _dumps_orjson
    return _dumps_stdlib


class StreamingBatchWriter:
    """Accumulate serialised items and emit complete request bodies.

    `flush_callback` receives `(body, item_count)` for each finished body. It is called on
    the adding thread, so a bounded callback is what applies back-pressure to the producer.

    `gzip_level` of None emits the body uncompressed, for a client configured with
    `enable_json_request_compression` off. `flush_interval_seconds` is evaluated when an
    item arrives, so it bounds how long a *trickle* of items leaves a batch open, not how
    long a producer that stops entirely does; `flush()` at the end of the upload covers
    that case.
    """

    def __init__(
        self,
        *,
        envelope: Mapping[str, Any],
        flush_callback: Callable[[bytes, int], None],
        max_payload_bytes: int,
        max_items: int,
        flush_interval_seconds: Optional[float] = None,
        gzip_level: Optional[int],
        use_orjson: bool = True,
    ) -> None:
        self._flush_callback = flush_callback
        self._max_payload_bytes = max_payload_bytes
        self._max_items = max_items
        self._flush_interval_seconds = flush_interval_seconds
        self._gzip_level = gzip_level
        self._dumps = select_dumps(use_orjson)

        # Serialise the envelope once and splice the item array onto it, so the dataset
        # name and group id are escaped by the same serialiser as everything else. The
        # envelope always carries fields: splicing onto an empty `{}` would produce
        # `{,"items":[`, so this is not a shape to make optional later.
        envelope_bytes = self._dumps(dict(envelope))
        self._prefix = envelope_bytes[:-1] + b',"items":['
        self._suffix = b"]}"

        self._start_buffer()

    def _start_buffer(self) -> None:
        self._compressor = (
            None
            if self._gzip_level is None
            else zlib.compressobj(self._gzip_level, zlib.DEFLATED, _GZIP_WBITS)
        )
        self._chunks = [self._encode(self._prefix)]
        self._logical_bytes = 0
        self._items = 0
        self._opened_at = time.monotonic()

    def _encode(self, data: bytes) -> bytes:
        return data if self._compressor is None else self._compressor.compress(data)

    def _serialize(self, item: Mapping[str, Any]) -> bytes:
        try:
            return self._dumps(item)
        except Exception as exception:
            # Checked here rather than relying on a hashing or encoding step elsewhere to
            # raise first: a streaming writer may be the only component that touches the
            # item, and the caller needs a consistent error either way.
            raise ItemNotSerializableError(
                f"Dataset item is not JSON-serializable: {exception}"
            ) from exception

    def add(self, item: Mapping[str, Any]) -> None:
        payload = self._serialize(item)

        # Close the batch before an item that would take it past the cap rather than
        # after, so an item at or over the cap ends up in a request of its own: a request
        # rejected for its size then fails that one row instead of every row that shared
        # its batch. The comparison is `sequence_splitter`'s, strictness included, so an
        # input is grouped the way the splitter would have grouped it.
        # The `+ 1` is the comma that would join this item to the batch; the check only
        # runs when there is already an item for it to follow.
        if self._items > 0 and self._logical_bytes + 1 + len(payload) > (
            self._max_payload_bytes
        ):
            self.flush()

        separator = b"" if self._items == 0 else b","
        self._chunks.append(self._encode(separator + payload))
        self._logical_bytes += len(payload) + len(separator)
        self._items += 1

        if self._should_flush():
            self.flush()

    def _should_flush(self) -> bool:
        if self._logical_bytes >= self._max_payload_bytes:
            return True
        if self._items >= self._max_items:
            return True
        if self._flush_interval_seconds is not None:
            return time.monotonic() - self._opened_at >= self._flush_interval_seconds
        return False

    def flush(self) -> None:
        """Emit whatever is buffered. A no-op when nothing has been added."""
        if self._items == 0:
            return

        self._chunks.append(self._encode(self._suffix))
        if self._compressor is not None:
            self._chunks.append(self._compressor.flush(zlib.Z_FINISH))
        body = b"".join(self._chunks)
        item_count = self._items

        self._start_buffer()
        self._flush_callback(body, item_count)


class BoundedSendPool:
    """Send finished bodies, with only so many outstanding at once.

    The bound is the point: without it a producer that serialises faster than the network
    drains would turn "never materialise the upload" back into "materialise it as queued
    request bodies". `submit` blocks once `num_threads * 2` bodies are outstanding, so
    memory stays bounded by the bodies in flight.

    `ThreadPoolExecutor` does the thread handling: it grows a worker per submitted body up
    to `num_threads` rather than starting them up front, and needs no shutdown protocol of
    its own. With a single worker the body is sent inline, which keeps the common case free
    of threads. The first failure is re-raised to the producer; as before there is no
    rollback, so bodies already accepted stay persisted.
    """

    def __init__(
        self,
        send: Callable[[bytes], None],
        num_threads: int,
        max_pending: Optional[int] = None,
    ) -> None:
        self._send = send
        self._max_pending = max_pending if max_pending is not None else num_threads * 2
        self._pending: Set["futures.Future[None]"] = set()
        self._pool: Optional[futures.ThreadPoolExecutor] = (
            futures.ThreadPoolExecutor(max_workers=num_threads)
            if num_threads > 1
            else None
        )

    def submit(self, body: bytes, item_count: int) -> None:
        LOGGER.debug("Sending dataset items batch of size %d", item_count)
        if self._pool is None:
            self._send(body)
            return

        # Waiting here is the back-pressure: the producer cannot run ahead of the network
        # by more than the bodies this set holds.
        if len(self._pending) >= self._max_pending:
            done, self._pending = futures.wait(
                self._pending, return_when=futures.FIRST_COMPLETED
            )
            for future in done:
                future.result()

        self._pending.add(self._pool.submit(self._send, body))

    def close(self) -> None:
        if self._pool is None:
            return
        try:
            for future in futures.as_completed(self._pending):
                future.result()
        finally:
            self._pool.shutdown(wait=True)


@overload
def canonical_id(value: None) -> None: ...


@overload
def canonical_id(value: Any) -> str: ...


def canonical_id(value: Any) -> Optional[str]:
    """The one form of an item identifier, for the wire and for anything keyed by it.

    `DatasetItem` declares its identifiers `SkipValidation[str]` and so passes through
    whatever it was given -- a `uuid.UUID` object, most usefully. Everything that has to
    agree on what an item *is* goes through here, the request body and the caches keyed by
    id alike, so an id and the form it was sent in cannot become two identities for one
    item.
    """
    return value if value is None or isinstance(value, str) else str(value)


def validate_identifier(value: Any, field: str, index: Optional[int] = None) -> None:
    """Reject an identifier the backend cannot store, before the request goes out.

    `id`, `trace_id` and `span_id` are UUID columns server-side, so anything else is
    refused there with a deserialisation error that names neither the item nor the field.
    `DatasetItem` declares them `SkipValidation[str]` and a prepared body never passes
    through the generated model, so this is the only check between the caller and that.
    """
    canonical = canonical_id(value)
    if canonical is None:
        return
    try:
        uuid.UUID(canonical)
    except ValueError:
        where = "" if index is None else f" at index {index}"
        raise ValueError(
            f"Dataset item{where} has an invalid {field}: {value!r} is not a UUID"
        ) from None


def item_payload(
    *,
    item_id: Optional[str],
    trace_id: Optional[str],
    span_id: Optional[str],
    source: Optional[str],
    data: Mapping[str, Any],
    description: Optional[str],
    evaluators: Optional[Any],
    execution_policy: Optional[Any],
) -> Dict[str, Any]:
    """The wire form of one dataset item.

    Mirrors the fields the generated client sends for `DatasetItemWrite`, and only those:
    the model declares `tags` as well, but the conversion never sets it, and the generated
    client omits fields that were never set while serialising explicit `None`s as null.
    Anything added here that the generated client does not send would change the request.

    The three identifiers go through `canonical_id`, because `DatasetItem` passes whatever
    it was given straight through and a `uuid.UUID` is an identifier that simply is not a
    string yet. Anything that is not a UUID in either form is refused before this, by
    `validate_identifier`.
    """
    return {
        "id": canonical_id(item_id),
        "trace_id": canonical_id(trace_id),
        "span_id": canonical_id(span_id),
        "source": source,
        "data": data,
        "description": description,
        "evaluators": evaluators,
        "execution_policy": execution_policy,
    }
