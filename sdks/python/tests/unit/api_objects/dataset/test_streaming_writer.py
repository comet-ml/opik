import gzip
import json

import pytest

from opik.api_objects.dataset import streaming_writer


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

    with pytest.raises(streaming_writer.ItemNotSerializableError):
        writer.add({"id": "a", "data": {"bad": {1, 2, 3}}})


def test_select_dumps__orjson_disabled__falls_back_to_the_standard_library():
    assert streaming_writer.select_dumps(use_orjson=False) is streaming_writer._dumps_stdlib


def test_dumps__orjson_and_stdlib__decode_to_the_same_value():
    """The wire serialisers may differ in bytes, but not in meaning."""
    pytest.importorskip("orjson")
    value = {"b": 2, "a": {"nested": [1, 2, "ü"]}, "n": None}

    stdlib = streaming_writer.select_dumps(use_orjson=False)(value)
    fast = streaming_writer.select_dumps(use_orjson=True)(value)

    assert json.loads(stdlib) == json.loads(fast) == value
