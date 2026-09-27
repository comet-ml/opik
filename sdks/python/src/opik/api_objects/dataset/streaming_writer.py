"""Build dataset-item request bodies as rows arrive, instead of materialising the upload.

A row is serialised once as it is added and appended to the batch being built; the pieces
of a finished body go to a callback when a size or count threshold trips, and are gzipped
by whoever sends them. What the writer holds is one body in flight, never the upload.

Serialisation here is for the wire only. Content hashes are computed elsewhere, with the
standard library, so item identity never depends on which serialiser is in use.
"""

from typing import Any, Callable, Dict, List, Mapping, Optional

from .. import constants, streaming_upload
from . import identifiers


class ItemNotSerializableError(TypeError):
    """A dataset item could not be serialised to JSON.

    Raised explicitly rather than left to surface from whichever component happens to
    serialise first, so the failure names the item and does not depend on the wire
    serialiser in use.
    """


class StreamingBatchWriter:
    """Accumulate serialised items and emit complete request bodies.

    `flush_callback` receives `(chunks, item_count, body_bytes)` for each finished body:
    the raw pieces of one request, for the sender to join and compress. It takes ownership
    of `chunks`. Called on the adding thread, so a bounded callback is what applies
    back-pressure to the producer.
    """

    def __init__(
        self,
        *,
        envelope: Mapping[str, Any],
        flush_callback: Callable[[List[bytes], int, int], None],
        max_payload_bytes: int,
        max_items: int,
    ) -> None:
        self._flush_callback = flush_callback
        self._max_payload_bytes = max_payload_bytes
        self._max_items = max_items

        # Serialise the envelope once and splice the item array onto it, so the dataset
        # name and group id are escaped by the same serialiser as everything else. The
        # envelope always carries fields: splicing onto an empty `{}` would produce
        # `{,"items":[`, so this is not a shape to make optional later.
        envelope_bytes = streaming_upload.dumps(dict(envelope))
        self._prefix = envelope_bytes[:-1] + b',"items":['
        self._suffix = b"]}"
        # `max_payload_bytes` caps the request body, and the envelope is part of that
        # body, so it has to be inside the budget rather than added after the decision
        # to flush. Counting only the items let every request run over the cap by this
        # many bytes, which grows with the dataset and project names.
        self._envelope_bytes = len(self._prefix) + len(self._suffix)

        self._start_buffer()

    def _start_buffer(self) -> None:
        self._chunks = [self._prefix]
        self._logical_bytes = 0
        self._items = 0

    def _serialize(self, item: Mapping[str, Any]) -> bytes:
        try:
            return streaming_upload.dumps(item)
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
        if self._items > 0 and self._body_bytes() + 1 + len(payload) > (
            self._max_payload_bytes
        ):
            self.flush()

        # The comma is a chunk of its own rather than prepended to the payload:
        # `b"," + payload` would copy the whole row to add one byte, which at this row
        # size is a fresh buffer per item for nothing. Same bytes either way.
        if self._items > 0:
            self._chunks.append(b",")
            self._logical_bytes += 1
        self._chunks.append(payload)
        self._logical_bytes += len(payload)
        self._items += 1

        if self._should_flush():
            self.flush()

    def _body_bytes(self) -> int:
        """The size of the request body as it stands, envelope included."""
        return self._envelope_bytes + self._logical_bytes

    def _should_flush(self) -> bool:
        return (
            self._body_bytes() >= self._max_payload_bytes
            or self._items >= self._max_items
        )

    def flush(self) -> None:
        """Emit whatever is buffered. A no-op when nothing has been added."""
        if self._items == 0:
            return

        self._chunks.append(self._suffix)
        chunks, item_count = self._chunks, self._items
        # The pool would otherwise re-derive this by summing every chunk, on the very
        # thread this class exists to keep free.
        body_bytes = self._body_bytes()

        self._start_buffer()
        self._flush_callback(chunks, item_count, body_bytes)


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
    flush_callback: Callable[[List[bytes], int, int], None],
) -> StreamingBatchWriter:
    """The writer one dataset upload sends through.

    The batch caps live here rather than at the call site, so what bounds a request is
    decided in one place for every caller instead of being passed in and possibly
    differing between them. Compression is not among them: the writer emits raw chunks
    and `BoundedSendPool` gzips them.
    """
    return StreamingBatchWriter(
        envelope={
            "dataset_name": dataset_name,
            "project_name": project_name,
            "batch_group_id": batch_group_id,
        },
        flush_callback=flush_callback,
        max_payload_bytes=streaming_upload.max_batch_bytes(),
        max_items=constants.DATASET_ITEMS_MAX_BATCH_SIZE,
    )
