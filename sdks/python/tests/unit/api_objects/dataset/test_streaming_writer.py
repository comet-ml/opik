import datetime
import decimal
import enum
import gzip
import json
import uuid

import pytest

from opik.api_objects.dataset import streaming_writer
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
        "flush_interval_seconds": None,
        "gzip_level": 6,
        "use_orjson": False,
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


def test_flush__time_threshold__sends_a_partial_batch_under_trickle():
    """Neither size nor count trips, so only elapsed time can send this."""
    bodies, flush_callback = _collect()
    writer = _writer(
        flush_callback,
        max_payload_bytes=10**9,
        max_items=10**6,
        flush_interval_seconds=0.05,
    )

    writer.add({"id": "first"})
    assert bodies == [], "Nothing should be sent before the interval elapses"

    import time

    time.sleep(0.06)
    writer.add({"id": "second"})

    assert len(bodies) == 1, "The elapsed interval should have flushed the buffer"
    assert bodies[0][1] == 2


def test_flush__no_items__does_nothing():
    bodies, flush_callback = _collect()
    _writer(flush_callback).flush()
    assert bodies == []


def test_body__matches_a_one_shot_gzip_of_the_same_rows():
    """Incremental compression must produce the same bytes as compressing once."""
    bodies, flush_callback = _collect()
    writer = _writer(flush_callback, gzip_level=6)

    rows = [{"id": f"item-{i}", "data": {"v": i}} for i in range(5)]
    for row in rows:
        writer.add(row)
    writer.flush()

    incremental = bodies[0][0]
    one_shot_payload = (
        json.dumps(
            {"dataset_name": "d", "project_name": None, "batch_group_id": "g"}
        ).encode("utf-8")[:-1]
        + b',"items":['
        + b",".join(json.dumps(row).encode("utf-8") for row in rows)
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


def test_pool__workers_exited__fails_instead_of_blocking_for_ever():
    """`SystemExit` leaves no consumer, and the queue is bounded: an insert must not hang."""

    def send(body: bytes) -> None:
        raise SystemExit("interrupted")

    pool = streaming_writer.BoundedSendPool(send, num_threads=2, max_pending=1)

    with pytest.raises(streaming_writer.SendWorkersGoneError):
        # More bodies than the queue holds, so a put has to wait on a worker that is gone.
        for _ in range(5):
            pool.submit(b"body", 1)


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
    writer = _writer(flush_callback, max_payload_bytes=300)

    for i in range(10):
        writer.add({"id": f"item-{i}", "data": {"padding": "x" * 60}})
    writer.flush()

    for body, _ in bodies:
        payload = _decode(body)
        assert len(json.dumps(payload["items"]).encode("utf-8")) <= 300 or (
            len(payload["items"]) == 1
        ), "Only a single oversized item may fill a request past the cap"
