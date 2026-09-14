"""Build dataset-item request bodies as rows arrive, instead of materialising the upload.

A row is serialised once as it is added and fed straight into a zlib stream; a finished
body goes to a callback when a size, count or time threshold trips. What the writer holds
is one body in flight, never the upload.

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
import uuid
import zlib
from concurrent import futures
from typing import Any, Callable, Dict, Mapping, Optional, Set

import pydantic

from ... import config
from .. import constants
from . import identifiers
from ...rest_api.core.jsonable_encoder import jsonable_encoder

LOGGER = logging.getLogger(__name__)

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


def _ordered_set_members(value: Any) -> list:
    """A set's members in an order that does not vary between processes.

    Python randomises string hashing per process, so `list()` over a set of strings comes
    out differently each run. That order reaches `content_hash` -- `sort_keys=True` orders
    a dict's keys, never a list's members -- and a digest that moves between processes
    means the same item deduplicates against itself in one run and uploads twice in the
    next.

    Sorted on the type name before the repr, because a set may legitimately mix types and
    `sorted` alone raises on `{1, "a"}`. The order this produces is arbitrary rather than
    natural -- "10" sorts before "9" -- which is all a canonical form has to be.
    """
    return sorted(value, key=lambda member: (type(member).__name__, repr(member)))


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
    if isinstance(value, (set, frozenset)):
        return _ordered_set_members(value)
    if isinstance(value, tuple):
        # A tuple's order is the caller's, unlike a set's, so it is kept as given.
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


def dumps(value: Any) -> bytes:
    """The wire form of one value.

    `default=` carries the flexible types the generated client used to accept, so only a
    value that needs the normalisation pays for it.
    """
    return json.dumps(value, default=encode_flexible).encode("utf-8")


class StreamingBatchWriter:
    """Accumulate serialised items and emit complete request bodies.

    `flush_callback` receives `(body, item_count)` for each finished body. It is called on
    the adding thread, so a bounded callback is what applies back-pressure to the producer.

    `gzip_level` of None emits the body uncompressed, for a client configured with
    `enable_json_request_compression` off.
    """

    def __init__(
        self,
        *,
        envelope: Mapping[str, Any],
        flush_callback: Callable[[bytes, int], None],
        max_payload_bytes: int,
        max_items: int,
        gzip_level: Optional[int],
    ) -> None:
        self._flush_callback = flush_callback
        self._max_payload_bytes = max_payload_bytes
        self._max_items = max_items
        self._gzip_level = gzip_level

        # Serialise the envelope once and splice the item array onto it, so the dataset
        # name and group id are escaped by the same serialiser as everything else. The
        # envelope always carries fields: splicing onto an empty `{}` would produce
        # `{,"items":[`, so this is not a shape to make optional later.
        envelope_bytes = dumps(dict(envelope))
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

    def _encode(self, data: bytes) -> bytes:
        return data if self._compressor is None else self._compressor.compress(data)

    def _serialize(self, item: Mapping[str, Any]) -> bytes:
        try:
            return dumps(item)
        except Exception as exception:
            # Checked here rather than relying on a hashing or encoding step elsewhere to
            # raise first: a streaming writer may be the only component that touches the
            # item, and the caller needs a consistent error either way.
            raise ItemNotSerializableError(
                f"Dataset item is not JSON-serializable: {exception}"
            ) from exception

    def add(self, item: Mapping[str, Any]) -> None:
        payload = self._serialize(item)

        # Closing before the offending item, rather than after, is what puts an item at
        # or over the cap alone in its own request: a request rejected for its size then
        # fails that one row instead of every row that shared a batch with it. The `+ 1`
        # is the comma that would join this item to the one before it.
        if self._items > 0 and self._logical_bytes + 1 + len(payload) > (
            self._max_payload_bytes
        ):
            self.flush()

        # The comma goes into the stream on its own rather than being prepended to the
        # payload: `b"," + payload` would copy the whole row to add one byte, which at
        # this row size is a fresh buffer per item for nothing. Same bytes either way.
        if self._items > 0:
            self._chunks.append(self._encode(b","))
            self._logical_bytes += 1
        self._chunks.append(self._encode(payload))
        self._logical_bytes += len(payload)
        self._items += 1

        if self._should_flush():
            self.flush()

    def _should_flush(self) -> bool:
        return (
            self._logical_bytes >= self._max_payload_bytes
            or self._items >= self._max_items
        )

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
    request bodies". `submit` blocks once `num_threads * 2` bodies are outstanding.

    `ThreadPoolExecutor` grows a worker per submitted body up to `num_threads`, so a small
    upload never starts the full ceiling; a single worker sends inline and starts no thread
    at all. The first failure is re-raised to the producer. There is no rollback, so bodies
    already accepted stay persisted.
    """

    def __init__(
        self,
        *,
        send: Callable[[bytes], None],
        num_threads: int,
        max_pending: int,
    ) -> None:
        self._send = send
        self._max_pending = max_pending
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

    The three identifiers go through `identifiers.optional_canonical_id`, because
    `DatasetItem` passes whatever it was given straight through and a `uuid.UUID` is an
    identifier that simply is not a string yet. Anything that is not a UUID in either
    form is refused before this, by `identifiers.validate_identifier`.
    """
    return {
        "id": identifiers.optional_canonical_id(item_id),
        "trace_id": identifiers.optional_canonical_id(trace_id),
        "span_id": identifiers.optional_canonical_id(span_id),
        "source": source,
        "data": data,
        "description": description,
        "evaluators": evaluators,
        "execution_policy": execution_policy,
    }


def build_batch_writer(
    *,
    dataset_name: str,
    project_name: Optional[str],
    batch_group_id: str,
    flush_callback: Callable[[bytes, int], None],
    gzip_level: Optional[int],
) -> StreamingBatchWriter:
    """The writer one dataset upload sends through.

    The batch caps live here rather than at the call site, so what bounds a request is
    decided in one place for every caller instead of being passed in and possibly
    differing between them. `gzip_level` stays a parameter because only the caller knows
    whether its transport expects a compressed body.
    """
    return StreamingBatchWriter(
        envelope={
            "dataset_name": dataset_name,
            "project_name": project_name,
            "batch_group_id": batch_group_id,
        },
        flush_callback=flush_callback,
        max_payload_bytes=int(config.MAX_BATCH_SIZE_MB * 1024 * 1024),
        max_items=constants.DATASET_ITEMS_MAX_BATCH_SIZE,
        gzip_level=gzip_level,
    )


def build_send_pool(send: Callable[[bytes], None], num_threads: int) -> BoundedSendPool:
    """The upload sink for one insert.

    Owns the one derivation the pool used to make for itself: twice the worker count,
    so a worker that finishes has a body waiting without the producer running arbitrarily
    far ahead. Passing it in explicitly keeps the pool free of a default that decided
    policy where it could not be seen.
    """
    return BoundedSendPool(
        send=send, num_threads=num_threads, max_pending=num_threads * 2
    )
