import contextlib
import dataclasses
import datetime
import decimal
import enum
import gzip
import json
import threading
import uuid

import pydantic
import pytest

from opik.api_objects.dataset import streaming_writer
from opik.rest_api.types.dataset_item_write import DatasetItemWrite
from opik.rest_api.core.jsonable_encoder import jsonable_encoder


class Color(enum.Enum):
    RED = "red"


def _collect():
    bodies = []

    def flush_callback(body: bytes, count: int) -> None:
        bodies.append((body, count))

    return bodies, flush_callback


def _writer(flush_callback, **kwargs):
    params = {
        "envelope": {"dataset_name": "d", "project_name": None, "batch_group_id": "g"},
        "flush_callback": flush_callback,
        "max_payload_bytes": 1_000_000,
        "max_items": 1_000,
        "gzip_level": 6,
        # The shipped default. Tests that build a stdlib expectation pass
        # `use_orjson=False` explicitly; everything else runs what users run.
        "use_orjson": True,
    }
    params.update(kwargs)
    return streaming_writer.StreamingBatchWriter(**params)


def _decode(body: bytes) -> dict:
    return json.loads(gzip.decompress(body))


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


@pytest.mark.parametrize("use_orjson", [False, True])
def test_body__matches_a_one_shot_gzip_of_the_same_rows(use_orjson):
    """Incremental compression must produce the same bytes as compressing once."""
    if use_orjson:
        pytest.importorskip("orjson")
    dumps = streaming_writer.select_dumps(use_orjson)
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback, gzip_level=6, use_orjson=use_orjson)

    rows = [{"id": f"item-{i}", "data": {"v": i}} for i in range(5)]
    for row in rows:
        writer.add(row)
    writer.flush()

    incremental = bodies[0][0]
    one_shot_payload = (
        dumps({"dataset_name": "d", "project_name": None, "batch_group_id": "g"})[:-1]
        + b',"items":['
        + b",".join(dumps(row) for row in rows)
        + b"]}"
    )

    assert gzip.decompress(incremental) == one_shot_payload, (
        "The decompressed body must match a single-pass serialisation of the same rows"
    )


def test_body__envelope_and_items_round_trip():
    bodies, flush_callback = _collect()
    writer = _writer(
        flush_callback,
        envelope={"dataset_name": "ds", "project_name": "proj", "batch_group_id": "bg"},
    )
    writer.add({"id": "a", "data": {"k": "v"}})
    writer.flush()

    payload = _decode(bodies[0][0])
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


def test_add__not_serializable_with_orjson__raises_the_same_error():
    """orjson raises a different exception type; callers must not have to know that."""
    pytest.importorskip("orjson")
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback, use_orjson=True)

    class NotSerializable:
        pass

    with pytest.raises(streaming_writer.ItemNotSerializableError):
        writer.add({"id": "a", "data": {"bad": NotSerializable()}})


def test_select_dumps__orjson_disabled__falls_back_to_the_standard_library():
    assert (
        streaming_writer.select_dumps(use_orjson=False)
        is streaming_writer._dumps_stdlib
    )


def test_dumps__orjson_and_stdlib__decode_to_the_same_value():
    """The wire serialisers may differ in bytes, but not in meaning."""
    pytest.importorskip("orjson")
    value = {"b": 2, "a": {"nested": [1, 2, "ü"]}, "n": None}

    stdlib = streaming_writer.select_dumps(use_orjson=False)(value)
    fast = streaming_writer.select_dumps(use_orjson=True)(value)

    assert json.loads(stdlib) == json.loads(fast) == value


# --------------------------------------------------------------------------- #
# values the generated client accepted: the writer must not narrow them
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize("use_orjson", [False, True])
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
def test_add__flexible_value__serialised_the_way_the_generated_client_did(
    value, use_orjson
):
    """`datetime`, `set`, `Enum` and friends reached the backend before; they still must."""
    if use_orjson:
        pytest.importorskip("orjson")
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback, use_orjson=use_orjson)

    writer.add({"id": "a", "data": {"v": value}})
    writer.flush()

    sent = _decode(bodies[0][0])["items"][0]["data"]["v"]
    assert sent == json.loads(json.dumps(jsonable_encoder(value)))


@pytest.mark.parametrize("use_orjson", [False, True])
def test_add__non_string_mapping_keys__coerced_like_json_dumps(use_orjson):
    """`json.dumps` turns these keys into strings; orjson rejects them by default."""
    if use_orjson:
        pytest.importorskip("orjson")
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback, use_orjson=use_orjson)

    writer.add({"id": "a", "data": {1: "x", 2.5: "y", None: "z"}})
    writer.flush()

    assert _decode(bodies[0][0])["items"][0]["data"] == json.loads(
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
# compression can be turned off
# --------------------------------------------------------------------------- #
def test_flush__compression_disabled__body_is_plain_json():
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback, gzip_level=None)

    writer.add({"id": "a", "data": {"k": "v"}})
    writer.flush()

    body = bodies[0][0]
    assert json.loads(body)["items"] == [{"id": "a", "data": {"k": "v"}}]
    assert not body.startswith(b"\x1f\x8b"), "The body must not be a gzip stream"


def test_flush__compression_enabled__body_is_still_gzip():
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback)

    writer.add({"id": "a", "data": {"k": "v"}})
    writer.flush()

    assert _decode(bodies[0][0])["items"] == [{"id": "a", "data": {"k": "v"}}]


# --------------------------------------------------------------------------- #
# BoundedSendPool shutdown
# --------------------------------------------------------------------------- #
def test_pool__worker_error__is_reraised_to_the_producer():
    def send(body: bytes) -> None:
        raise ValueError("rejected")

    pool = streaming_writer.BoundedSendPool(send, num_threads=2)
    pool.submit(b"body", 1)

    with pytest.raises(ValueError):
        pool.close()


def _executor_threads(pool) -> int:
    """How many threads the executor has actually started.

    `ThreadPoolExecutor._threads` is a stdlib internal, and the only observer of the
    property under test: nothing downstream distinguishes three workers from sixty-four,
    which is why starting them eagerly went unnoticed before.
    """
    return len(pool._pool._threads)


def test_pool__workers_follow_the_upload_not_the_ceiling():
    """`num_threads` is a ceiling. A three-body upload must not start sixty-four threads."""
    sent = []
    pool = streaming_writer.BoundedSendPool(sent.append, num_threads=64)

    assert _executor_threads(pool) == 0, "No worker before there is a body to send"

    for index in range(3):
        pool.submit(f"body-{index}".encode(), 1)
    pool.close()

    # Asserted together on purpose: a pool that started no threads because it sent
    # nothing would satisfy the worker count on its own.
    assert sorted(sent) == [b"body-0", b"body-1", b"body-2"], (
        "Every body must still have been sent, exactly once"
    )
    assert _executor_threads(pool) <= 3, "Started a thread with no body for it"


def test_pool__sustained_load__grows_to_the_ceiling_and_no_further():
    """Growing lazily must not cost concurrency when the upload actually needs it."""
    release = threading.Event()
    started = threading.Semaphore(0)
    sent = []

    def blocked_send(body: bytes) -> None:
        sent.append(body)
        started.release()
        release.wait(5)

    pool = streaming_writer.BoundedSendPool(blocked_send, num_threads=8)
    try:
        for expected in range(1, 9):
            pool.submit(b"body", 1)
            # Wait for the body to be picked up, so the next submit sees no idle worker.
            assert started.acquire(timeout=5), "the body was never picked up"
            assert _executor_threads(pool) == expected, (
                "A body with every worker busy should have grown the pool"
            )

        pool.submit(b"body", 1)
        assert _executor_threads(pool) == 8, "num_threads is a ceiling and must hold"

        release.set()
        pool.close()
        assert len(sent) == 9, "Every body must still have been sent"
    finally:
        release.set()


def test_pool__saturated__submit_blocks_until_a_body_lands():
    """The back-pressure that keeps memory bounded: the producer cannot run ahead."""
    release = threading.Event()

    def blocked_send(body: bytes) -> None:
        release.wait(5)

    # num_threads=2 bounds the outstanding bodies at 4.
    pool = streaming_writer.BoundedSendPool(blocked_send, num_threads=2)
    returned = threading.Event()

    def fill_and_overflow() -> None:
        for _ in range(4):
            pool.submit(b"body", 1)
        pool.submit(b"body", 1)  # the fifth has to wait for one of the four
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


def test_pool__send_raising_a_base_exception__reaches_the_producer():
    """`SystemExit` from a send must surface, not be swallowed or left hanging."""

    def send(body: bytes) -> None:
        raise SystemExit("interrupted")

    pool = streaming_writer.BoundedSendPool(send, num_threads=2)
    pool.submit(b"body", 1)

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

    batches = [_decode(body)["items"] for body, _ in bodies]
    assert [[item["id"] for item in batch] for batch in batches] == [
        ["small"],
        ["big"],
        ["also-small"],
    ]


def test_add__batch_never_exceeds_the_payload_cap():
    """Closing the batch before the item that would overflow it, not after."""
    bodies, flush_callback = _collect()
    # On the standard library, so the size the assertion recomputes below is the one the
    # writer measured; the cap arithmetic itself does not depend on the serialiser.
    writer = _writer(flush_callback, max_payload_bytes=300, use_orjson=False)

    for i in range(10):
        writer.add({"id": f"item-{i}", "data": {"padding": "x" * 60}})
    writer.flush()

    for body, _ in bodies:
        payload = _decode(body)
        assert len(json.dumps(payload["items"]).encode("utf-8")) <= 300 or (
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


# --------------------------------------------------------------------------- #
# the platform where orjson is not installed at all
# --------------------------------------------------------------------------- #
def test_select_dumps__orjson_absent__uses_the_standard_library(monkeypatch):
    """orjson is not required on Windows ARM64 below 3.11, where no wheel is published.

    `enable_orjson_serialization` still defaults to True there, so asking for orjson when
    the import failed has to degrade rather than raise.
    """
    monkeypatch.setattr(streaming_writer, "orjson", None)

    assert (
        streaming_writer.select_dumps(use_orjson=True) is streaming_writer._dumps_stdlib
    )


def test_writer__orjson_absent__still_serialises_flexible_values(monkeypatch):
    """The upload path must not assume orjson anywhere behind the selection."""
    monkeypatch.setattr(streaming_writer, "orjson", None)
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback)  # the shipped default: use_orjson=True

    writer.add({"id": "a", "data": {"when": datetime.date(2024, 1, 2), "n": 2**70}})
    writer.flush()

    sent = _decode(bodies[0][0])["items"][0]["data"]
    assert sent == {"when": "2024-01-02", "n": 2**70}


def test_content_hash__digest_does_not_depend_on_orjson(monkeypatch):
    """Digests must not vary by platform, or dedup breaks for anyone moving between them."""
    from opik.api_objects.dataset import dataset_item

    content = {"input": {"nested": [1, "two", None]}, "when": datetime.date(2024, 1, 2)}
    with_orjson = dataset_item.DatasetItem(**content).content_hash()

    monkeypatch.setattr(streaming_writer, "orjson", None)
    without_orjson = dataset_item.DatasetItem(**content).content_hash()

    assert with_orjson == without_orjson


def test_pool__a_body_fails__surfaces_to_the_producer_at_the_bound():
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

    pool = streaming_writer.BoundedSendPool(send, num_threads=2)  # a bound of four
    try:
        attempts = 0
        with pytest.raises(ValueError, match="body-0 was rejected"):
            for index in range(20):
                attempts += 1
                pool.submit(f"body-{index}".encode(), 1)

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


@pytest.mark.parametrize("use_orjson", [False, True])
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
def test_add__object_with_no_json_form__raises_wherever_it_is(value, use_orjson):
    """`jsonable_encoder`'s last resort is `vars(obj)`, which would upload it as a dict.

    The encoder hook hands back the shell of anything with an interior so the serialiser
    walks back into this check for each member; without that, an object inside a set,
    dataclass or model was encoded by its attributes and uploaded silently.
    """
    if use_orjson:
        pytest.importorskip("orjson")
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback, use_orjson=use_orjson)

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

    assert _decode(bodies[0][0])["items"][0]["data"]["v"] == expected
