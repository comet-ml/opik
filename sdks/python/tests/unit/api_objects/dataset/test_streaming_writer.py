import contextlib
import dataclasses
import datetime
import decimal
import enum
import gzip
import json
import threading
import uuid
import zlib

import pydantic
import pytest

from opik import config
from opik.api_objects.dataset import streaming_writer
from opik.rest_api.types.dataset_item_write import DatasetItemWrite
from opik.rest_api.core.jsonable_encoder import jsonable_encoder


class Color(enum.Enum):
    RED = "red"


def _collect():
    bodies = []

    def flush_callback(chunks, count: int, body_bytes: int) -> None:
        body = b"".join(chunks)
        assert body_bytes == len(body), (
            "The writer must report the size of the body it just emitted"
        )
        bodies.append((body, count))

    return bodies, flush_callback


@pytest.fixture
def tiny_batch_bytes(monkeypatch):
    """Shrink the batch cap so a bound test costs kilobytes instead of tens of MB.

    `BoundedSendPool` reads it once in `__init__`, so this must be applied before the
    pool is built. Same branches, ~500x less memory.
    """
    monkeypatch.setattr(config, "MAX_BATCH_SIZE_MB", 0.01)
    return int(config.MAX_BATCH_SIZE_MB * 1024 * 1024)


@pytest.fixture
def make_pool():
    """A closed-on-teardown pool. A failed assertion must not leak parked workers."""
    pools = []

    def build(**kwargs) -> streaming_writer.BoundedSendPool:
        pool = streaming_writer.BoundedSendPool(**kwargs)
        pools.append(pool)
        return pool

    yield build
    for pool in pools:
        # `close` re-raises whatever a worker failed with, and some of these tests fail
        # a send with `SystemExit` on purpose; the test has already asserted on it, so
        # teardown only has to get the threads stopped. Not `BaseException` -- Ctrl-C on
        # a hung run has to keep working.
        with contextlib.suppress(Exception, SystemExit):
            pool.close()


# Width of the identifying prefix the pool tests stamp on a body.
_MARKER = 16


def _submit(pool, chunks, item_count: int = 1) -> None:
    """`submit` takes the size the writer measured; here we measure it the same way."""
    pool.submit(chunks, item_count, sum(len(chunk) for chunk in chunks))


def _writer(flush_callback, **kwargs):
    params = {
        "envelope": {"dataset_name": "d", "project_name": None, "batch_group_id": "g"},
        "flush_callback": flush_callback,
        "max_payload_bytes": 1_000_000,
        "max_items": 1_000,
    }
    params.update(kwargs)
    return streaming_writer.StreamingBatchWriter(**params)


def test_flush__byte_threshold__splits_into_several_requests():
    bodies, flush_callback = _collect()
    # Each item serialises to well over 50 bytes, so the size threshold is what trips.
    writer = _writer(flush_callback, max_payload_bytes=50)

    for i in range(4):
        writer.add({"id": f"item-{i}", "data": {"padding": "x" * 40}})
    writer.flush()

    assert len(bodies) == 4, "Each item should exceed the byte threshold on its own"
    assert sum(count for _, count in bodies) == 4


def test_flush__count_threshold__splits_on_item_count():
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback, max_items=2)

    for i in range(5):
        writer.add({"id": f"item-{i}"})
    writer.flush()

    assert [count for _, count in bodies] == [2, 2, 1]


def test_flush__no_items__does_nothing():
    bodies, flush_callback = _collect()
    _writer(flush_callback).flush()
    assert bodies == []


def test_body__matches_a_one_shot_serialisation_of_the_same_rows():
    """Row-at-a-time assembly must produce the bytes one pass over the rows would."""
    dumps = streaming_writer.dumps
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback)

    rows = [{"id": f"item-{i}", "data": {"v": i}} for i in range(5)]
    for row in rows:
        writer.add(row)
    writer.flush()

    assembled = bodies[0][0]
    one_shot_payload = (
        dumps({"dataset_name": "d", "project_name": None, "batch_group_id": "g"})[:-1]
        + b',"items":['
        + b",".join(dumps(row) for row in rows)
        + b"]}"
    )

    assert assembled == one_shot_payload, (
        "The body must match a single-pass serialisation of the same rows"
    )


def test_body__envelope_and_items_round_trip():
    bodies, flush_callback = _collect()
    writer = _writer(
        flush_callback,
        envelope={"dataset_name": "ds", "project_name": "proj", "batch_group_id": "bg"},
    )
    writer.add({"id": "a", "data": {"k": "v"}})
    writer.flush()

    payload = json.loads(bodies[0][0])
    assert payload["dataset_name"] == "ds"
    assert payload["project_name"] == "proj"
    assert payload["batch_group_id"] == "bg"
    assert payload["items"] == [{"id": "a", "data": {"k": "v"}}]


def test_add__value_not_json_serializable__raises_explicitly():
    """The check must be the writer's own, not a side effect of something else."""
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback)

    class NotSerializable:
        pass

    with pytest.raises(streaming_writer.ItemNotSerializableError):
        writer.add({"id": "a", "data": {"bad": NotSerializable()}})


# --------------------------------------------------------------------------- #
# values the generated client accepted: the writer must not narrow them
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize(
    "value",
    [
        datetime.datetime(2024, 1, 2, 3, 4, 5, tzinfo=datetime.timezone.utc),
        datetime.datetime(2024, 1, 2, 3, 4, 5),
        datetime.date(2024, 1, 2),
        datetime.time(3, 4, 5),
        {"only"},
        (1, 2),
        Color.RED,
        b"hi",
        decimal.Decimal("1.5"),
        uuid.UUID("00000000-0000-0000-0000-000000000001"),
    ],
)
def test_add__flexible_value__serialised_the_way_the_generated_client_did(value):
    """`datetime`, `set`, `Enum` and friends reached the backend before; they still must."""
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback)

    writer.add({"id": "a", "data": {"v": value}})
    writer.flush()

    sent = json.loads(bodies[0][0])["items"][0]["data"]["v"]
    assert sent == json.loads(json.dumps(jsonable_encoder(value)))


def test_add__non_string_mapping_keys__coerced_like_json_dumps():
    """`json.dumps` coerces non-string keys; the wire form must keep doing so."""
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback)

    writer.add({"id": "a", "data": {1: "x", 2.5: "y", None: "z"}})
    writer.flush()

    assert json.loads(bodies[0][0])["items"][0]["data"] == json.loads(
        json.dumps({1: "x", 2.5: "y", None: "z"})
    )


def test_add__unknown_object__still_raises_rather_than_being_encoded_as_empty():
    """The encoder's own last resort is `vars(obj)`; uploading `{}` would hide the error."""
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback)

    class Custom:
        def __init__(self):
            self.x = 1

    with pytest.raises(streaming_writer.ItemNotSerializableError):
        writer.add({"id": "a", "data": {"bad": Custom()}})


# --------------------------------------------------------------------------- #
# compression happens on the sender, and can be turned off
# --------------------------------------------------------------------------- #
def test_pool__compression_enabled__body_is_one_gzip_stream_of_the_batch(make_pool):
    sent = []
    pool = make_pool(send=sent.append, num_threads=2, gzip_level=6)

    chunks = [b'{"items":[', b'{"id": "a"}', b"]}"]
    expected = b"".join(chunks)  # snapshot: submit takes ownership and empties the list
    _submit(pool, chunks)
    pool.close()

    assert sent[0].startswith(b"\x1f\x8b"), "A gzipped body must be labelled as one"
    assert gzip.decompress(sent[0]) == expected, (
        "The worker must send exactly the batch it was handed"
    )
    assert chunks == [], (
        "The raw pieces must not outlive the body they were compressed into"
    )


@pytest.mark.parametrize(
    "n_chunks",
    [
        pytest.param(
            streaming_writer._COMPRESS_BLOCK_CHUNKS - 1,
            id="one-short-block",
        ),
        pytest.param(
            streaming_writer._COMPRESS_BLOCK_CHUNKS,
            id="exactly-one-block",
        ),
        pytest.param(
            streaming_writer._COMPRESS_BLOCK_CHUNKS + 1,
            id="block-plus-a-tail",
        ),
        pytest.param(
            streaming_writer._COMPRESS_BLOCK_CHUNKS * 3 + 7,
            id="several-blocks",
        ),
    ],
)
def test_pool__body_spanning_several_compression_blocks__round_trips(
    n_chunks, make_pool
):
    """The batch is fed to zlib a slice at a time, so the seams have to be invisible.

    Every other compressed-path test sends one short body, which never crosses a block
    boundary: a loop that dropped the final partial slice, or emitted slices out of
    order, would pass all of them. The real writer emits ~2000 chunks per batch.
    """
    sent = []
    pool = make_pool(send=sent.append, num_threads=2, gzip_level=6)

    chunks = [f"<{index}>".encode() for index in range(n_chunks)]
    expected = b"".join(chunks)
    _submit(pool, chunks)
    pool.close()

    assert gzip.decompress(sent[0]) == expected, (
        "Every slice must reach the compressor exactly once, and in order"
    )
    assert chunks == [], "Each slice must be released as it is consumed"
    # `gzip.decompress` joins a multi-member stream silently, so decoding cleanly does
    # not prove the slices went through one compressor. The class promises one stream
    # per body, and a per-slice `compressobj` would satisfy every assertion above.
    decompressor = zlib.decompressobj(streaming_writer._GZIP_WBITS)
    decompressor.decompress(sent[0])
    assert decompressor.unused_data == b"", "A body must be exactly one gzip member"


def test_pool__compression_disabled__body_is_plain_json(make_pool):
    sent = []
    pool = make_pool(send=sent.append, num_threads=2, gzip_level=None)

    _submit(pool, [b'{"items":[', b'{"id": "a"}', b"]}"])
    pool.close()

    assert json.loads(sent[0])["items"] == [{"id": "a"}]
    assert not sent[0].startswith(b"\x1f\x8b"), "The body must not be a gzip stream"


# --------------------------------------------------------------------------- #
# BoundedSendPool shutdown
# --------------------------------------------------------------------------- #
def test_pool__worker_error__is_reraised_to_the_producer(make_pool):
    def send(body: bytes) -> None:
        raise ValueError("rejected")

    pool = make_pool(send=send, num_threads=2, gzip_level=None)
    _submit(pool, [b"body"])

    with pytest.raises(ValueError):
        pool.close()


def _executor_threads(pool) -> int:
    """How many threads the executor has actually started.

    `ThreadPoolExecutor._threads` is a stdlib internal, and the only observer of the
    property under test: nothing downstream distinguishes three workers from sixty-four,
    which is why starting them eagerly went unnoticed before.
    """
    return len(pool._pool._threads)


def test_pool__workers_follow_the_upload_not_the_ceiling(make_pool):
    """`num_threads` is a ceiling. A three-body upload must not start sixty-four threads."""
    sent = []
    pool = make_pool(send=sent.append, num_threads=64, gzip_level=None)

    assert _executor_threads(pool) == 0, "No worker before there is a body to send"

    for index in range(3):
        _submit(pool, [f"body-{index}".encode()])
    pool.close()

    # Asserted together on purpose: a pool that started no threads because it sent
    # nothing would satisfy the worker count on its own.
    assert sorted(sent) == [b"body-0", b"body-1", b"body-2"], (
        "Every body must still have been sent, exactly once"
    )
    assert _executor_threads(pool) <= 3, "Started a thread with no body for it"


def test_pool__sustained_load__grows_to_the_ceiling_and_no_further(make_pool):
    """Growing lazily must not cost concurrency when the upload actually needs it."""
    release = threading.Event()
    started = threading.Semaphore(0)
    sent = []

    def blocked_send(body: bytes) -> None:
        sent.append(body)
        started.release()
        release.wait(5)

    pool = make_pool(send=blocked_send, num_threads=8, gzip_level=None)
    try:
        for expected in range(1, 9):
            _submit(pool, [b"body"])
            # Wait for the body to be picked up, so the next submit sees no idle worker.
            assert started.acquire(timeout=5), "the body was never picked up"
            assert _executor_threads(pool) == expected, (
                "A body with every worker busy should have grown the pool"
            )

        _submit(pool, [b"body"])
        assert _executor_threads(pool) == 8, "num_threads is a ceiling and must hold"

        release.set()
        pool.close()
        assert len(sent) == 9, "Every body must still have been sent"
    finally:
        release.set()


def test_pool__saturated__submit_blocks_until_a_body_lands(make_pool):
    """The back-pressure that keeps memory bounded: the producer cannot run ahead."""
    release = threading.Event()

    def blocked_send(body: bytes) -> None:
        release.wait(5)

    # num_threads=2 bounds the outstanding bodies at 4.
    pool = make_pool(send=blocked_send, num_threads=2, gzip_level=None)
    returned = threading.Event()

    def fill_and_overflow() -> None:
        for _ in range(4):
            _submit(pool, [b"body"])
        _submit(pool, [b"body"])  # the fifth has to wait for one of the four
        returned.set()

    producer = threading.Thread(target=fill_and_overflow, daemon=True)
    producer.start()
    try:
        assert not returned.wait(0.5), (
            "The producer ran past the bound instead of waiting for a body to land"
        )
    finally:
        release.set()

    assert returned.wait(5), "The producer never resumed once a body had landed"
    producer.join(5)
    pool.close()


def test_pool__oversized_bodies__submit_blocks_on_bytes_before_the_count(
    make_pool, tiny_batch_bytes
):
    """The byte bound exists for bodies the count cannot bound: ones bigger than a batch.

    An item over the batch cap gets a request to itself, so `num_threads * 2` bodies can
    weigh far more than `num_threads * 2` batches. Two double-size bodies already fill a
    four-batch budget, and the third must wait even though the count allows it.
    """
    release = threading.Event()

    def blocked_send(body: bytes) -> None:
        release.wait(5)

    # num_threads=2: four bodies allowed, four batches of bytes allowed.
    pool = make_pool(send=blocked_send, num_threads=2, gzip_level=None)
    returned = threading.Event()

    # A fresh list per submit, as the writer allocates one per flush: `submit` takes
    # ownership and empties what it is handed, so a reused list would weigh nothing.
    def oversized() -> list:
        return [b"x" * (2 * tiny_batch_bytes)]

    def fill_and_overflow() -> None:
        _submit(pool, oversized())
        _submit(pool, oversized())
        _submit(pool, oversized())  # within the count, past the bytes
        returned.set()

    producer = threading.Thread(target=fill_and_overflow, daemon=True)
    producer.start()
    try:
        assert not returned.wait(0.5), (
            "The producer queued more raw bytes than the bound allows"
        )
        release.set()
        assert returned.wait(5), "The producer never resumed once a body had landed"
    finally:
        # In `finally` so an assertion failure above still tears the pool down rather
        # than leaving its workers parked in `blocked_send` for the rest of the session.
        release.set()
        producer.join(5)
        pool.close()


def test_pool__body_larger_than_the_whole_budget__is_admitted_anyway(
    make_pool, tiny_batch_bytes
):
    """The worker floor outranks the byte budget, or one huge item would idle everyone.

    Documented as a real limit rather than a bound that holds: for items bigger than the
    budget the ceiling is `num_threads` of them, not the bytes.

    The floor is also what keeps `_collect` from ever being reached with nothing pending.
    A pool exists only for `num_threads > 1`, so the floor is at least 2, and
    `_at_capacity` short-circuits below it -- without that, `futures.wait` on an empty
    set would return instantly and `submit` would spin. Deleting the floor hangs this
    test rather than failing it, which is the shape of the bug it guards against.
    """
    sent = []
    pool = make_pool(send=sent.append, num_threads=2, gzip_level=None)

    _submit(pool, [b"x" * (10 * tiny_batch_bytes)])
    pool.close()

    assert len(sent) == 1, "A body over the budget must still be sent, not blocked on"


def test_pool__send_raising_a_base_exception__reaches_the_producer(make_pool):
    """`SystemExit` from a send must surface, not be swallowed or left hanging."""

    def send(body: bytes) -> None:
        raise SystemExit("interrupted")

    pool = make_pool(send=send, num_threads=2, gzip_level=None)
    _submit(pool, [b"body"])

    with pytest.raises(SystemExit):
        pool.close()


def test_add__item_at_the_cap__gets_its_own_request():
    """The splitter always gave an oversized item a request to itself; so must the writer.

    Otherwise a batch that is rejected for its size takes otherwise-valid rows with it.
    """
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback, max_payload_bytes=200)

    writer.add({"id": "small"})
    writer.add({"id": "big", "data": {"padding": "x" * 300}})
    writer.add({"id": "also-small"})
    writer.flush()

    batches = [json.loads(body)["items"] for body, _ in bodies]
    assert [[item["id"] for item in batch] for batch in batches] == [
        ["small"],
        ["big"],
        ["also-small"],
    ]


def test_add__batch_never_exceeds_the_payload_cap():
    """Closing the batch before the item that would overflow it, not after."""
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback, max_payload_bytes=300)

    for i in range(10):
        writer.add({"id": f"item-{i}", "data": {"padding": "x" * 60}})
    writer.flush()

    for body, _ in bodies:
        payload = json.loads(body)
        # Re-encoded through the writer's own encoder rather than the standard library:
        # the cap arithmetic does not depend on the serialiser, but the size does, and
        # measuring it a second way would test the second way instead.
        assert len(streaming_writer.dumps(payload["items"])) <= 300 or (
            len(payload["items"]) == 1
        ), "Only a single oversized item may fill a request past the cap"


# --------------------------------------------------------------------------- #
# the hand-written mapper against the model it mirrors
# --------------------------------------------------------------------------- #
def _generated_form(**payload):
    """What the generated client puts on the wire when built from the same payload.

    `exclude_unset` is how it omits fields the conversion never touched -- `tags` -- while
    still serialising an explicit `None` as null.
    """
    return json.loads(DatasetItemWrite(**payload).json(exclude_unset=True))


@pytest.mark.parametrize(
    "evaluators, execution_policy",
    [
        pytest.param(None, None, id="both-null"),
        pytest.param(
            [{"name": "judge", "type": "llm_judge", "config": {"model": "gpt-4"}}],
            {"runs_per_item": 3, "pass_threshold": 2},
            id="both-set",
        ),
        pytest.param(
            [{"name": "judge", "type": "llm_judge", "config": {}}],
            None,
            id="evaluators-only",
        ),
        pytest.param(None, {"runs_per_item": 1, "pass_threshold": 1}, id="policy-only"),
    ],
)
def test_item_payload__matches_the_generated_model(evaluators, execution_policy):
    """The mapper is hand-written; the model it mirrors is generated and can move."""
    payload = streaming_writer.item_payload(
        item_id="item-1",
        trace_id=None,
        span_id=None,
        source="sdk",
        data={"input": "q"},
        description=None,
        evaluators=evaluators,
        execution_policy=execution_policy,
    )

    assert json.loads(json.dumps(payload)) == _generated_form(**payload), (
        "The hand-written payload and the generated model disagree on the wire form"
    )


def test_item_payload__field_names_match_the_model():
    """A field the model drops, or one it has that we never send, both matter."""
    payload = streaming_writer.item_payload(
        item_id="item-1",
        trace_id="t",
        span_id="s",
        source="sdk",
        data={"input": "q"},
        description="d",
        evaluators=None,
        execution_policy=None,
    )

    model_fields = set(DatasetItemWrite.model_fields)
    assert set(payload) <= model_fields, (
        f"Payload carries fields the model does not: {set(payload) - model_fields}"
    )
    assert model_fields - set(payload) == {"tags"}, (
        "Only `tags` should be absent; the conversion has never set it"
    )


def test_item_payload__explicit_nulls_are_sent_not_omitted():
    """`DatasetItem` leaves these unset far more often than not, and null is the signal."""
    payload = streaming_writer.item_payload(
        item_id=None,
        trace_id=None,
        span_id=None,
        source="sdk",
        data={"input": "q"},
        description=None,
        evaluators=None,
        execution_policy=None,
    )

    assert payload == {
        "id": None,
        "trace_id": None,
        "span_id": None,
        "source": "sdk",
        "data": {"input": "q"},
        "description": None,
        "evaluators": None,
        "execution_policy": None,
    }


def test_writer__date_and_oversized_int__serialise_on_the_wire():
    """Two values the writer must not narrow: `json.dumps` writes both, so it has to."""
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback)

    writer.add({"id": "a", "data": {"when": datetime.date(2024, 1, 2), "n": 2**70}})
    writer.flush()

    sent = json.loads(bodies[0][0])["items"][0]["data"]
    assert sent == {"when": "2024-01-02", "n": 2**70}


def test_pool__a_failure_collected__does_not_strand_the_budget(
    make_pool, tiny_batch_bytes
):
    """A caught failure must not strand the budget, nor cost a body that was accepted.

    The accounting is asserted directly because nothing behavioural can catch it: the
    `num_threads` floor admits a body once the pool drains below the worker count, so a
    producer resumes even with the budget completely stranded. Delivery is asserted
    alongside it, since an implementation that dropped successful bodies would keep the
    counter and the book agreeing perfectly.
    """
    lock = threading.Lock()
    delivered = []
    accepted = []

    def send(body: bytes) -> None:
        with lock:
            delivered.append(body[:_MARKER])
        # Keyed on the body, not on which worker arrives first: with two workers racing,
        # an "is this the first send" flag fails a different body on every run.
        if body.startswith(b"reject-me"):
            raise ValueError("reject-me was rejected")

    pool = make_pool(send=send, num_threads=2, gzip_level=None)

    def submit_one(marker: bytes) -> None:
        """Record the body only once `submit` has taken it: a submit that raises at the
        bound is reporting an *earlier* body's failure and never accepted this one."""
        _submit(pool, [marker.ljust(_MARKER, b"-") + b"x" * (2 * tiny_batch_bytes)])
        accepted.append(marker.ljust(_MARKER, b"-"))

    try:
        # Four bodies allowed, four batches of bytes; each of these weighs two, so the
        # producer waits every other submit and the failure surfaces at the bound.
        with pytest.raises(ValueError, match="reject-me was rejected"):
            submit_one(b"reject-me")
            for index in range(10):
                submit_one(f"fine-{index}".encode())

        # The counter agrees with the book. A `_collect` that pops a landed future
        # without discharging its bytes breaks this, and nothing behavioural would
        # notice -- the worker floor lets the producer run on regardless.
        assert pool._pending_bytes == sum(pool._pending.values())

        finished = threading.Event()

        def keep_going() -> None:
            for index in range(4):
                submit_one(f"after-{index}".encode())
            finished.set()

        producer = threading.Thread(target=keep_going, daemon=True)
        producer.start()
        assert finished.wait(5), "The producer never resumed after the failure"
        producer.join(5)

        assert pool._pending_bytes == sum(pool._pending.values())
    finally:
        pool.close()

    assert sorted(delivered) == sorted(accepted), (
        "Every body the pool accepted must reach `send` exactly once, the rejected one "
        "included -- a failure must not cost the bodies queued beside it"
    )


def test_pool__a_body_fails__surfaces_to_the_producer_at_the_bound(make_pool):
    """The failure has to reach the producer at the bound, not only at `close()`.

    `submit` collects finished futures once `num_threads * 2` are outstanding; that
    collection is the only thing standing between a failed body and a producer that would
    otherwise keep serialising into an upload that is already broken.

    Which body fails is fixed rather than "whichever ran first", and every other body waits
    for it, so the submit that raises is the same one on any scheduling.
    """
    failed = threading.Event()

    def send(body: bytes) -> None:
        if body == b"body-0":
            failed.set()
            raise ValueError("body-0 was rejected")
        failed.wait(5)

    pool = make_pool(send=send, num_threads=2, gzip_level=None)  # a bound of four
    try:
        attempts = 0
        with pytest.raises(ValueError, match="body-0 was rejected"):
            for index in range(20):
                attempts += 1
                _submit(pool, [f"body-{index}".encode()])

        # Four submits fill the bound; the fifth waits, collects the failed future and
        # raises.
        assert attempts == 5, (
            "The failure must surface on the submit that waits at the bound, not at close"
        )

        # Reported once, not twice, because the wait above took the failed future out of
        # the pending set -- so this close has only successful ones left to collect.
        # `Dataset.insert` leans on that when it closes the pool in its `except` branch;
        # `test_insert__producer_error_with_a_worker_error_pending__producer_error_wins`
        # covers that at the insert level, this test does not reach `insert` at all.
        pool.close()
    finally:
        # Release anything still waiting and shut the executor down even if an assertion
        # above failed, so a broken assertion here cannot leak threads into the rest of
        # the file. `close` keeps its pending set, and a failure still in it re-raises on
        # every call, so this one suppresses rather than assuming it is a no-op.
        failed.set()
        with contextlib.suppress(Exception):
            pool.close()


# --------------------------------------------------------------------------- #
# an object with no JSON form is refused wherever it hides
# --------------------------------------------------------------------------- #
class _NoJsonForm:
    """Not serialisable, and not one of the types the encoder converts."""

    def __init__(self) -> None:
        self.attribute = 1


@dataclasses.dataclass
class _Holder:
    inner: object


class _ModelHolder(pydantic.BaseModel):
    model_config = pydantic.ConfigDict(arbitrary_types_allowed=True)
    inner: object


@pytest.mark.parametrize(
    "value",
    [
        pytest.param(_NoJsonForm(), id="top-level"),
        pytest.param({"deep": _NoJsonForm()}, id="in-a-dict"),
        pytest.param([_NoJsonForm()], id="in-a-list"),
        pytest.param((_NoJsonForm(),), id="in-a-tuple"),
        pytest.param({_NoJsonForm()}, id="in-a-set"),
        pytest.param(frozenset({_NoJsonForm()}), id="in-a-frozenset"),
        pytest.param(_Holder(_NoJsonForm()), id="in-a-dataclass"),
        pytest.param(_ModelHolder(inner=_NoJsonForm()), id="in-a-pydantic-model"),
    ],
)
def test_add__object_with_no_json_form__raises_wherever_it_is(value):
    """`jsonable_encoder`'s last resort is `vars(obj)`, which would upload it as a dict.

    The encoder hook hands back the shell of anything with an interior so the serialiser
    walks back into this check for each member; without that, an object inside a set,
    dataclass or model was encoded by its attributes and uploaded silently.
    """
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback)

    with pytest.raises(streaming_writer.ItemNotSerializableError):
        writer.add({"id": "a", "data": {"v": value}})

    assert bodies == [], "Nothing may be emitted for an item that cannot be sent"


@pytest.mark.parametrize(
    "value, expected",
    [
        pytest.param({"only"}, ["only"], id="set"),
        pytest.param(frozenset({"only"}), ["only"], id="frozenset"),
        pytest.param((1, 2), [1, 2], id="tuple"),
        pytest.param(_Holder(5), {"inner": 5}, id="dataclass"),
        pytest.param(
            _ModelHolder(inner="text"), {"inner": "text"}, id="pydantic-model"
        ),
        pytest.param(
            {datetime.date(2024, 1, 2)}, ["2024-01-02"], id="date-inside-a-set"
        ),
    ],
)
def test_add__value_with_an_interior__still_encoded_as_it_was(value, expected):
    """Handing back the shell must not change what an acceptable value looks like."""
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback)

    writer.add({"id": "a", "data": {"v": value}})
    writer.flush()

    assert json.loads(bodies[0][0])["items"][0]["data"]["v"] == expected


def test_encode_flexible__set__ordered_canonically_not_by_iteration():
    """Sorted, so the result cannot depend on a per-process hash seed.

    Asserted against the sorted order rather than against another `list()` of the same
    set: within one process those agree whatever the rule is, so that comparison would
    hold even for the iteration order this replaced.
    """
    assert streaming_writer.encode_flexible({"gamma", "alpha", "delta", "beta"}) == [
        "alpha",
        "beta",
        "delta",
        "gamma",
    ]
    assert streaming_writer.encode_flexible(frozenset({"b", "a"})) == ["a", "b"]


def test_encode_flexible__set_of_one_comparable_type__natural_order():
    """The common path: members that compare are sorted directly, not by their repr.

    Pinned because the two disagree -- by repr `10` precedes `2` -- so this is what
    fixes which of them is the identity a stored row is matched against.
    """
    assert streaming_writer.encode_flexible({10, 1, 2}) == [1, 2, 10]
    assert streaming_writer.encode_flexible({"b", "a", "c"}) == ["a", "b", "c"]


def test_encode_flexible__set_of_mixed_types__ordered_by_type_then_repr():
    """`sorted` alone raises on `{1, "a"}`; a set may legitimately hold both.

    The exact list, not the members: sorting both sides of the comparison would check
    only that nothing was lost, and would hold however the order moved -- which is the
    one thing this has to pin.

    `NoneType` < `float` < `int` < `str` is the type name deciding, before the repr ever
    comes into it, which is why `2.5` precedes `1`.
    """
    assert streaming_writer.encode_flexible({1, "a", None, 2.5}) == [None, 2.5, 1, "a"]


def test_encode_flexible__tuple__keeps_the_order_it_was_given():
    """A tuple is ordered by the caller, unlike a set, so canonicalising it would lose data."""
    assert streaming_writer.encode_flexible(("z", "a", "m")) == ["z", "a", "m"]
