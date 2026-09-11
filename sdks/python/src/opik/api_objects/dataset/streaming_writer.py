"""Build dataset-item request bodies as rows arrive, instead of materialising the upload.

The batching path accumulates every item, splits the list into batches, then serialises and
compresses each batch. That holds the whole upload in memory and walks each item several
times over. This writer serialises a row once as it is added, feeds the bytes straight into
a zlib stream, and hands a finished request body to a callback when a threshold trips, so
peak memory tracks one in-flight body rather than the upload.

Serialisation here is for the wire only. Content hashes are computed elsewhere, with the
standard library, so item identity never depends on which serialiser is in use.
"""

import json
import logging
import time
import zlib
from typing import Any, Callable, Dict, Mapping, Optional

LOGGER = logging.getLogger(__name__)

try:
    import orjson
except ImportError:  # pragma: no cover - exercised by the kill-switch test
    orjson = None  # type: ignore[assignment]

# gzip container rather than a raw deflate stream, matching what the server expects.
_GZIP_WBITS = 16 + zlib.MAX_WBITS


class ItemNotSerializableError(TypeError):
    """A dataset item could not be serialised to JSON.

    Raised explicitly rather than left to surface from whichever component happens to
    serialise first, so the failure names the item and does not depend on the wire
    serialiser in use.
    """


def _dumps_stdlib(value: Any) -> bytes:
    return json.dumps(value).encode("utf-8")


def _dumps_orjson(value: Any) -> bytes:
    return orjson.dumps(value)


def select_dumps(use_orjson: bool) -> Callable[[Any], bytes]:
    """Pick the wire serialiser. Falls back to the standard library when orjson is absent."""
    if use_orjson and orjson is not None:
        return _dumps_orjson
    return _dumps_stdlib


class StreamingBatchWriter:
    """Accumulate serialised items and emit complete, gzipped request bodies.

    `flush_callback` receives `(body, item_count)` for each finished body. It is called on
    the adding thread, so a bounded callback is what applies back-pressure to the producer.
    """

    def __init__(
        self,
        *,
        envelope: Mapping[str, Any],
        flush_callback: Callable[[bytes, int], None],
        max_payload_bytes: int,
        max_items: int,
        flush_interval_seconds: Optional[float] = None,
        gzip_level: int = 6,
        use_orjson: bool = True,
    ) -> None:
        self._flush_callback = flush_callback
        self._max_payload_bytes = max_payload_bytes
        self._max_items = max_items
        self._flush_interval_seconds = flush_interval_seconds
        self._gzip_level = gzip_level
        self._dumps = select_dumps(use_orjson)

        # Serialise the envelope once and splice the item array onto it, so the dataset
        # name and group id are escaped by the same serialiser as everything else.
        envelope_bytes = self._dumps(dict(envelope))
        self._prefix = envelope_bytes[:-1] + b',"items":['
        self._suffix = b"]}"

        self._start_buffer()

    def _start_buffer(self) -> None:
        self._compressor = zlib.compressobj(
            self._gzip_level, zlib.DEFLATED, _GZIP_WBITS
        )
        self._chunks = [self._compressor.compress(self._prefix)]
        self._logical_bytes = 0
        self._items = 0
        self._opened_at = time.monotonic()

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

        separator = b"" if self._items == 0 else b","
        self._chunks.append(self._compressor.compress(separator + payload))
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

        self._chunks.append(self._compressor.compress(self._suffix))
        self._chunks.append(self._compressor.flush(zlib.Z_FINISH))
        body = b"".join(self._chunks)
        item_count = self._items

        self._start_buffer()
        self._flush_callback(body, item_count)


class BoundedSendPool:
    """Send finished bodies from a bounded queue, optionally across worker threads.

    The bound is the point: without it a producer that serialises faster than the network
    drains would turn "never materialise the upload" back into "materialise it as queued
    request bodies". `submit` blocks once the queue is full, so memory stays bounded by the
    number of bodies in flight.

    With a single worker the body is sent inline, which keeps the common case free of
    threads. The first failure is re-raised to the producer; as before there is no
    rollback, so bodies already accepted stay persisted.
    """

    def __init__(
        self,
        send: Callable[[bytes], None],
        num_threads: int,
        max_pending: Optional[int] = None,
    ) -> None:
        self._send = send
        self._threaded = num_threads > 1
        self._error: Optional[BaseException] = None

        if not self._threaded:
            return

        import queue
        import threading

        self._queue: "queue.Queue[Optional[bytes]]" = queue.Queue(
            maxsize=max_pending if max_pending is not None else num_threads * 2
        )
        self._lock = threading.Lock()
        self._workers = [
            threading.Thread(target=self._worker, daemon=True)
            for _ in range(num_threads)
        ]
        for worker in self._workers:
            worker.start()

    def _worker(self) -> None:
        while True:
            body = self._queue.get()
            try:
                if body is None:
                    return
                if self._error is None:
                    self._send(body)
            except BaseException as exception:  # noqa: BLE001 - re-raised in close()
                with self._lock:
                    if self._error is None:
                        self._error = exception
            finally:
                self._queue.task_done()

    def submit(self, body: bytes, item_count: int) -> None:
        LOGGER.debug("Sending dataset items batch of size %d", item_count)
        if not self._threaded:
            self._send(body)
            return
        self._raise_if_failed()
        self._queue.put(body)

    def close(self) -> None:
        if self._threaded:
            for _ in self._workers:
                self._queue.put(None)
            for worker in self._workers:
                worker.join()
        self._raise_if_failed()

    def _raise_if_failed(self) -> None:
        if self._error is not None:
            raise self._error


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
    """
    return {
        "id": item_id,
        "trace_id": trace_id,
        "span_id": span_id,
        "source": source,
        "data": data,
        "description": description,
        "evaluators": evaluators,
        "execution_policy": execution_policy,
    }
