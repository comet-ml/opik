"""The shared pieces of a streaming bulk upload: wire encoding, compression, sending.

Both bulk uploads -- dataset items and experiment items -- serialise each record once as
it arrives, splice the fragments into request bodies, and hand finished bodies to a
bounded pool that compresses and sends them. What each upload's envelope and batch caps
are is its own business, and lives with it; this module holds only what the two do the
same way, so one encoder decides the wire form of the flexible types for both and one
pool decides how far a producer may run ahead of the network.
"""

import dataclasses
import datetime
import decimal
import enum
import logging
import pathlib
import threading
import uuid
import zlib
from concurrent import futures
from typing import Any, Callable, Dict, List, Optional

import pydantic

from .. import config
from .. import json_helpers
from ..rest_api.core.jsonable_encoder import jsonable_encoder

LOGGER = logging.getLogger(__name__)


# How long `abort` waits for sends already started before leaving them behind.
ABORT_WAIT_SECONDS = 5.0


def max_batch_bytes() -> int:
    """The SDK's request-body byte cap, which the send pool budgets its queue in."""
    return int(config.MAX_BATCH_SIZE_MB * 1024 * 1024)


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


def _ordered_set_members(value: Any) -> list:
    """A set's members in an order that does not vary between processes.

    A set never survives the round trip: it goes out as a JSON array and comes back as a
    list, so its digest has to equal the digest of the array it becomes or it could never
    deduplicate against its own stored form. A canonical order is what makes those two
    agree, which is why this is a requirement and not tidiness. `list()` alone comes out
    differently in every process -- Python randomises string hashing -- and
    `sort_keys=True` orders a dict's keys, never a list's members.

    Natural order where the members compare, which is most sets and much the cheaper
    path. A set may legitimately mix types and `sorted` alone raises on `{1, "a"}`, so
    those fall back to the type name ahead of the repr; that order is arbitrary rather
    than meaningful, which is all a canonical form has to be.

    A row written before this was canonical holds an arbitrary order, so a set will not
    deduplicate against one. That is not recoverable from here -- the order differs per
    row and per writing process -- and it costs nothing that worked, because hashing a
    set raised `TypeError` before this change and such a row could never be deduplicated
    against at all.
    """
    try:
        return sorted(value)
    except TypeError:
        pass
    try:
        return sorted(value, key=lambda member: (type(member).__name__, repr(member)))
    except Exception as exception:
        # A member's own `__repr__` can raise anything; report it as unserialisable.
        raise TypeError(
            f"Set member cannot be ordered for serialization: {exception}"
        ) from exception


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
    return json_helpers.dumps(value, default=encode_flexible, sort_keys=False)


# Compress a slice at a time, not a chunk at a time. `compress()` releases the GIL but
# the loop around it does not, and a batch arrives as one piece per row plus its
# separators -- roughly two thousand at the item cap. Fed individually that is a GIL
# hand-off per piece, and throughput drops as workers are added instead of rising; a
# slice amortises it. The body is already capped, so a slice never exceeds one batch.
_COMPRESS_BLOCK_CHUNKS = 256


def gzip_chunks(chunks: List[bytes], level: int, *, release: bool = False) -> bytes:
    """One gzip stream over the pieces of a request body.

    Call this from the thread that will send the body, never from the one that built it:
    zlib releases the GIL, so compression is the part of an upload that actually
    parallelises across workers, and doing it on the producer funnels all of it through
    one thread.

    `release` empties `chunks` as it goes, so compressing never holds all of the batch
    raw and all of it compressed at once. A caller that may need the pieces afterwards --
    the experiment path re-sends a rejected batch as halves -- leaves it off and keeps
    them.
    """
    compressor = zlib.compressobj(level, zlib.DEFLATED, _GZIP_WBITS)
    parts: List[bytes] = []
    for start in range(0, len(chunks), _COMPRESS_BLOCK_CHUNKS):
        end = min(start + _COMPRESS_BLOCK_CHUNKS, len(chunks))
        parts.append(compressor.compress(b"".join(chunks[start:end])))
        if release:
            chunks[start:end] = [b""] * (end - start)
    parts.append(compressor.flush(zlib.Z_FINISH))
    body = b"".join(parts)
    parts.clear()
    return body


def encode_body(
    chunks: List[bytes], gzip_level: Optional[int], *, release: bool = False
) -> bytes:
    """One request body from its pieces, gzipped unless `gzip_level` is None.

    The uncompressed join needs every piece at once, so that copy is transient rather
    than progressive; `release` applies to the gzip path, which can give the pieces up as
    it consumes them. A caller that may need them afterwards -- the experiment path
    re-sends a rejected batch as halves -- leaves it off.
    """
    if gzip_level is None:
        return b"".join(chunks)
    return gzip_chunks(chunks, gzip_level, release=release)


class BoundedSendPool:
    """Compress finished bodies and send them, with only so many outstanding at once.

    Compression is where a producer thread spends most of its time, and zlib releases the
    GIL, so a batch is gzipped by the worker that will send it rather than by the thread
    that built it. One gzip stream per request body, as before. `gzip_level` of None sends
    the body uncompressed, for a client configured with `enable_json_request_compression`
    off.

    The bound is the point: without it a producer that serialises faster than the network
    drains would turn "never materialise the upload" back into "materialise it as queued
    request bodies". `submit` blocks once `num_threads * 2` bodies are outstanding, or
    once they weigh that many full batches -- the same ceiling counted two ways, so the
    byte bound only bites for bodies larger than a batch, which the count alone would not
    bound at all. Both derive from `num_threads`, so there is one number to change.

    That ceiling is not absolute: a body is admitted whenever fewer than `num_threads` are
    outstanding, however large it is, or an item bigger than the whole budget would idle
    every worker behind it. For oversized items the real bound is therefore `num_threads`
    of them, not the byte budget.

    `ThreadPoolExecutor` grows a worker per submitted body up to `num_threads`, so a small
    upload never starts the full ceiling; a single worker compresses and sends inline and
    starts no thread at all. The first failure is re-raised to the producer. There is no
    rollback, so bodies already accepted stay persisted.

    `send` is given the finished body and whatever `submit` carried alongside it. The
    dataset upload carries nothing; the experiment upload carries the record fragments the
    body was built from, because a batch the server rejects as too large is halved and
    re-sent and the bytes alone cannot be split back up.

    `fail_fast` decides what a failure costs the rest of the upload. Off -- the dataset
    path -- everything already queued is drained and awaited, so those bodies land and the
    error surfaces after them. On, a failure is recorded as it happens, so the next
    `submit` raises instead of feeding the pool further, and `close` aborts the rest the
    way `abort` does.

    `stop_event` is set by `abort`; a `send` that should give up early -- between
    rate-limit retries, say -- is handed the same event and checks it.
    """

    def __init__(
        self,
        *,
        send: Callable[[bytes, Any], None],
        num_threads: int,
        gzip_level: Optional[int],
        fail_fast: bool = False,
        thread_name_prefix: str = "",
        stop_event: Optional[threading.Event] = None,
    ) -> None:
        self._send = send
        self._stop = stop_event if stop_event is not None else threading.Event()
        self._gzip_level = gzip_level
        self._fail_fast = fail_fast
        self._first_error: Optional[BaseException] = None
        # Two bodies per worker, so one is always ready as the network drains the last.
        self._max_pending = num_threads * 2
        self._max_pending_bytes = self._max_pending * max_batch_bytes()
        # Never fewer bodies than workers, whatever the bytes say.
        self._min_pending = num_threads
        self._pending: Dict["futures.Future[None]", int] = {}
        self._pending_bytes = 0
        self._pool: Optional[futures.ThreadPoolExecutor] = (
            futures.ThreadPoolExecutor(
                max_workers=num_threads, thread_name_prefix=thread_name_prefix
            )
            if num_threads > 1
            else None
        )

    def _compress_and_send(self, chunks: List[bytes], payload: Any) -> None:
        """Send one body. Takes ownership of `chunks` and empties it.

        The pieces are released a slice at a time as they are compressed, so this never
        holds all of the batch raw and all of it compressed at once. Nothing here re-sends
        them: a caller that does re-send keeps its own reference to the fragments -- which
        is what `payload` carries -- rather than to this list.
        """
        body = encode_body(chunks, self._gzip_level, release=True)
        # Emptied before the send, never after: the executor holds this list for the whole
        # call, so a send parked in a read timeout, a retry or a 429 wait would otherwise
        # pin a second copy of the body.
        chunks.clear()
        self._send(body, payload)

    def _collect(self) -> None:
        """Wait for at least one body to land, and re-raise whatever it failed with."""
        done, _ = futures.wait(self._pending, return_when=futures.FIRST_COMPLETED)
        failure: Optional[BaseException] = None
        for future in done:
            # Everything that landed comes off the books before anything is raised, or a
            # producer that catches this and carries on would block against bytes it is
            # no longer using.
            self._pending_bytes -= self._pending.pop(future)
            if failure is None:
                failure = future.exception()
        if failure is not None:
            raise failure

    def _at_capacity(self, body_bytes: int) -> bool:
        """Whether one more body would exceed either bound, with the worker floor applied."""
        if len(self._pending) < self._min_pending:
            return False
        return (
            len(self._pending) >= self._max_pending
            or self._pending_bytes + body_bytes > self._max_pending_bytes
        )

    def _record_failure(self, future: "futures.Future[None]") -> None:
        """Note a failed body as it lands, rather than at the next collect.

        A producer that is not at capacity never collects, so without this it would keep
        feeding the pool until it was -- which is the opposite of failing fast.
        """
        if future.cancelled():
            return
        error = future.exception()
        if error is not None and self._first_error is None:
            self._first_error = error

    def submit(
        self,
        chunks: List[bytes],
        item_count: int,
        body_bytes: int,
        payload: Any = None,
    ) -> None:
        """Send one body, blocking while the pool is at capacity.

        Takes ownership of `chunks`: it is emptied on a worker thread at an unpredictable
        time, so a caller that reuses the list gets an empty request body. `payload` is
        handed back to `send` untouched, for a sender that needs more than the bytes.
        """
        LOGGER.debug("Sending items batch of size %d", item_count)
        if self._pool is None:
            self._compress_and_send(chunks, payload)
            return

        if self._first_error is not None:
            raise self._first_error

        # Waiting here is the back-pressure: the producer cannot run ahead of the network
        # by more than these bounds hold.
        while self._at_capacity(body_bytes):
            self._collect()

        # Charged only once there is a future to discharge it, so a submit that fails to
        # start a thread does not strand the bytes in the budget.
        future = self._pool.submit(self._compress_and_send, chunks, payload)
        self._pending[future] = body_bytes
        self._pending_bytes += body_bytes
        if self._fail_fast:
            future.add_done_callback(self._record_failure)

    def abort(self) -> None:
        """Stop the upload: signal the senders, drop what has not started, wait a bounded time.

        A send that checks the stop signal gives up before its next attempt, and a
        rate-limit wait ends as soon as the signal is set. A request already on the wire
        cannot be interrupted -- Python can neither cancel a blocking HTTP call nor kill a
        thread -- so it runs until it returns or times out. A worker still running after
        `ABORT_WAIT_SECONDS` is logged and left behind rather than waited on. If its send
        checks the signal it starts no further request, but executor threads are joined
        at interpreter exit, so it can still delay exit until its in-flight request -- or
        a retry backoff it is already sleeping through -- ends.
        """
        self._stop.set()
        if self._pool is None:
            return
        self._pool.shutdown(wait=False, cancel_futures=True)
        # A future cancelled by `shutdown` never counts as done to `futures.wait`.
        started = [future for future in self._pending if not future.cancelled()]
        _, still_running = futures.wait(started, timeout=ABORT_WAIT_SECONDS)
        if still_running:
            LOGGER.warning(
                "%d upload request(s) still running %s seconds after the upload was "
                "aborted; they cannot be interrupted mid-request and are left to finish "
                "in the background",
                len(still_running),
                ABORT_WAIT_SECONDS,
            )

    def close(self) -> None:
        if self._pool is None:
            return
        try:
            if self._first_error is not None:
                raise self._first_error
            for future in futures.as_completed(self._pending):
                future.result()
        except BaseException:
            # A fail-fast caller is not waiting for the rest: a body parked in the
            # rate-limit retry loop would otherwise hold the producer here.
            if self._fail_fast:
                self.abort()
            else:
                self._pool.shutdown(wait=True)
            raise
        else:
            self._pool.shutdown(wait=True)
