"""Item identity must not move for a given encoder, and dedup must not depend on the wire.

`content_hash` feeds duplicate detection. Its digests are process-local: `Dataset` never
reads a digest from the backend, it rebuilds `_hashes` by streaming the stored items down
and hashing them itself (`__internal_api__sync_hashes__`). So the items a pass compares
against were hashed by the same interpreter, in the same process, with whichever encoder
that process has -- there is no stored digest for a changed encoding to stop matching.

That is what lets `json_helpers` accelerate the digest where orjson is installed. What it
does not license is the digest drifting *within* one encoder, so the golden digests below
are still pinned, and still pinned to the standard library: it is the encoder every
platform has, the one the SDK has always used, and the only one whose bytes can be
written down here and checked anywhere. Tests that assert them therefore pin the
standard-library path explicitly rather than taking whatever the test machine installed.
"""

import dataclasses
import datetime
import decimal
import hashlib
import json
from unittest.mock import Mock

import pytest

from opik import json_helpers

try:
    import orjson
except ImportError:  # no wheel for this platform
    orjson = None
from opik.api_objects.dataset import dataset_item, streaming_writer
from opik.api_objects.dataset.dataset import Dataset

from .upload_capture import UploadCapture, make_dataset

ITEM_SHAPES = [
    pytest.param({"input": "plain"}, id="flat-string"),
    pytest.param({"b": 2, "a": 1}, id="keys-out-of-order"),
    pytest.param({"input": {"nested": {"deep": [1, 2, {"x": "y"}]}}}, id="nested"),
    pytest.param(
        {"input": "héllo wörld 🙂", "expected_output": "ünïcode"}, id="non-ascii"
    ),
    pytest.param(
        {"input": None, "expected_output": 0, "flag": False}, id="falsy-values"
    ),
    pytest.param({"input": {"a": 1.5, "b": [True, None]}}, id="mixed-scalars"),
]


@pytest.fixture
def stdlib_encoder(monkeypatch):
    """Force `json_helpers` down the standard-library path.

    Without this a golden digest would assert one thing on a machine with orjson and
    another without, which is the opposite of a pinned value.
    """
    monkeypatch.setattr(json_helpers, "_orjson", None)


def _legacy_digest(content: dict) -> str:
    """Exactly how the digest was produced before this change."""
    return hashlib.sha256(json.dumps(content, sort_keys=True).encode()).hexdigest()


@pytest.mark.parametrize("content", ITEM_SHAPES)
def test_content_hash__matches_the_legacy_digest(content, stdlib_encoder):
    item = dataset_item.DatasetItem(**content)
    assert item.content_hash() == _legacy_digest(item.get_content())


def test_content_hash__compact_separators_would_differ():
    """Guards the premise: the legacy form is not merely 'any json.dumps'."""
    content = {"a": 1, "b": 2}
    compact = hashlib.sha256(
        json.dumps(content, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    assert compact != _legacy_digest(content), (
        "If these ever match, this test no longer proves separators matter"
    )


def test_insert__dedup_is_independent_of_the_wire_form():
    """Identity comes from the digest, not from what the writer put on the wire."""
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)

    item = {"input": {"key": "value"}, "expected_output": {"key": "out"}}
    dataset.insert([item, item])

    assert len(capture.items) == 1, "The duplicate must be dropped"


def test_insert__duplicate_far_apart_in_the_stream__still_caught():
    """Dedup state has to span the whole pass, not just one batch."""
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)

    first = {"input": {"key": "first"}}
    filler = [{"input": {"key": f"filler-{i}"}} for i in range(50)]

    dataset.insert([first, *filler, dict(first)])

    inputs = [item["data"]["input"]["key"] for item in capture.items]
    assert inputs.count("first") == 1, (
        "A duplicate separated by many items must still be dropped"
    )
    assert len(capture.items) == 51


# --------------------------------------------------------------------------- #
# values that used to have no digest at all
# --------------------------------------------------------------------------- #
FLEXIBLE_SHAPES = [
    pytest.param(
        {"when": datetime.datetime(2024, 1, 2, 3, 4, 5, tzinfo=datetime.timezone.utc)},
        id="datetime",
    ),
    pytest.param({"raw": b"bytes"}, id="bytes"),
    pytest.param({"tags": {"a"}}, id="set"),
    pytest.param({"amount": decimal.Decimal("1.5")}, id="decimal"),
]


@pytest.mark.parametrize("content", FLEXIBLE_SHAPES)
def test_content_hash__flexible_value__hashes_instead_of_raising(content):
    """The upload accepts these, so deduplication -- the default -- must not reject them."""
    digest = dataset_item.DatasetItem(**content).content_hash()

    assert len(digest) == 64
    assert digest == dataset_item.DatasetItem(**content).content_hash(), (
        "The digest must be stable across calls"
    )


@pytest.mark.parametrize("content", ITEM_SHAPES)
def test_content_hash__ordinary_value__does_not_use_the_fallback(
    content, monkeypatch, stdlib_encoder
):
    """The fallback must be unreachable for anything that already serialises.

    Its bytes would be the same here, so equality with the legacy digest cannot prove the
    old path was taken; making the encoder explode does.
    """

    def explode(value):
        raise AssertionError("the flexible encoder must not be consulted")

    monkeypatch.setattr(streaming_writer, "encode_flexible", explode)

    assert dataset_item.DatasetItem(**content).content_hash() == _legacy_digest(
        dataset_item.DatasetItem(**content).get_content()
    )


def test_content_hash__unserializable_value__still_raises():
    class NotSerializable:
        pass

    # The object hashes fine by identity; it is serialising it to JSON that fails.
    with pytest.raises(TypeError):
        dataset_item.DatasetItem(input=NotSerializable()).content_hash()


# --------------------------------------------------------------------------- #
# the digests themselves, not just their agreement with a recomputation
# --------------------------------------------------------------------------- #
# Written down rather than derived: comparing against `_legacy_digest` proves only that
# two pieces of code agree, and both would move together if the encoding changed. These
# are the standard library's bytes -- the encoder every platform has, and the one this SDK
# has always used. A failure here means the standard-library digest moved, which is a
# dedup break for every install without orjson, never a test to update.
GOLDEN_DIGESTS = [
    pytest.param(
        {"input": "plain"},
        "600b81afbd8dce5513f499d27f3ea08ed5e6cf832f06fda3063d3020f68df563",
        id="flat-string",
    ),
    pytest.param(
        {"b": 2, "a": 1},
        "d8497d9d82770a70729261095aa98f7ef5154d7af499f8037b6ca250296785a6",
        id="keys-out-of-order",
    ),
    pytest.param(
        {"input": {"nested": {"deep": [1, 2, {"x": "y"}]}}},
        "22db775c8b17b17d508784e9483aa308ecfceb8bfab868d77f3f1882b6d281fa",
        id="nested",
    ),
    pytest.param(
        {"input": "héllo wörld 🙂", "expected_output": "ünïcode"},
        "ee04cc26d4a9e227b622640cef5c7cd438d554adbe349527a3fa59c358b108f2",
        id="non-ascii",
    ),
    pytest.param(
        {"input": None, "expected_output": 0, "flag": False},
        "a2d5992170a01f0113609c032f38bf26762f069551fafa347fc971a7887fe8dd",
        id="falsy-values",
    ),
    pytest.param(
        {"input": {"a": 1.5, "b": [True, None]}},
        "b371305d07e634117f65ae40c2e5aaad61fc1c3aa64d73df90ceeb7c54779080",
        id="mixed-scalars",
    ),
]


@pytest.mark.parametrize("content, digest", GOLDEN_DIGESTS)
def test_content_hash__matches_the_recorded_digest(content, digest, stdlib_encoder):
    assert dataset_item.DatasetItem(**content).content_hash() == digest


@dataclasses.dataclass
class _Holder:
    inner: object


def test_content_hash__object_with_no_json_form__raises_wherever_it_is():
    """Hashing and serialising must agree about what an item may contain.

    Both go through `encode_flexible`, so a value the upload would refuse must not hash
    either -- otherwise deduplication would accept an item the writer then rejects, and
    which of the two reported it would depend on `deduplication`.
    """

    class NoJsonForm:
        def __init__(self) -> None:
            self.attribute = 1

    for value in (
        NoJsonForm(),
        {"deep": NoJsonForm()},
        {NoJsonForm()},
        _Holder(NoJsonForm()),
    ):
        with pytest.raises(TypeError):
            dataset_item.DatasetItem(input={"v": value}).content_hash()


def test_content_hash__set_valued_item__digest_is_pinned(stdlib_encoder):
    """A set's members must reach the digest in an order no process can change.

    Python randomises string hashing per process, so `list()` over a set of strings comes
    out differently each run, and `sort_keys=True` orders a dict's keys but never a
    list's members. Before this was canonicalised the digest below differed in every
    process, which meant the same item deduplicated against itself in one run and
    uploaded twice in the next.

    Pinned rather than compared against a freshly computed digest, which would agree with
    itself however the order moved. Recorded from the canonical form, so a change to that
    order fails here instead of silently splitting stored items from new ones.
    """
    item = dataset_item.DatasetItem(input={"tags": {"alpha", "beta", "gamma", "delta"}})

    assert item.content_hash() == (
        "56d8f26305d963aa017a728776dbe66c69846651c498eec63b37b62bc24e5204"
    )


def test_content_hash__differs_between_encoders(monkeypatch):
    """The reason a digest must never travel, pinned as a fact rather than a worry.

    orjson writes compact separators and real UTF-8; the standard library writes
    ``", "`` and escapes non-ASCII. Same content, different bytes, different digest.
    Dedup survives this only because every digest it compares was computed by the
    client doing the comparing.
    """
    if orjson is None:
        pytest.skip("orjson ships no wheel for this platform")
    content = {"input": {"b": 2, "a": 1, "text": "héllo 🙂"}}

    monkeypatch.setattr(json_helpers, "_orjson", orjson)
    accelerated = dataset_item.DatasetItem(**content).content_hash()

    monkeypatch.setattr(json_helpers, "_orjson", None)
    stdlib = dataset_item.DatasetItem(**content).content_hash()

    assert accelerated != stdlib, (
        "If these ever match, a digest could safely be sent or stored -- and the "
        "recompute-on-sync rule this suite protects would no longer be load-bearing"
    )


def test_sync_hashes__recomputes_locally_rather_than_trusting_the_backend():
    """Dedup identity must come from this client's encoder, not from stored values.

    A digest read back from the backend would have been produced by whichever encoder
    that uploader had. Recomputing here is what keeps the comparison meaningful.

    So this drives the sync itself -- a backend already holding one item, the local
    cache marked stale -- and asserts what a caller can see: the copy of the stored
    item never leaves, the new one does. The stored item comes back with the
    backend's own id and its keys in another order, so a cache keyed on anything but
    recomputed content would miss the duplicate and upload it.
    """
    stored = {"input": {"key": "value"}, "expected_output": {"key": "out"}}
    backend_item = dataset_item.DatasetItem(
        id="backend-assigned-id",
        expected_output={"key": "out"},
        input={"key": "value"},
    )

    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)
    dataset.__internal_api__stream_items_as_dataclasses__ = lambda *_, **__: iter(
        [backend_item]
    )
    dataset.__internal_api__hashes_synced__ = False

    fresh = {"input": {"key": "other"}, "expected_output": {"key": "out"}}
    dataset.insert([stored, fresh])

    assert [item["data"]["input"] for item in capture.items] == [{"key": "other"}], (
        "The item the backend already holds must be recognised from its content alone"
    )
