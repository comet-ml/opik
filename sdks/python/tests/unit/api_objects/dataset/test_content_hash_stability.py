"""Item identity must not move when the wire serialiser changes.

`content_hash` feeds duplicate detection, and the digests of items already stored were
produced by `json.dumps(..., sort_keys=True)` with default separators. A compact
serialiser produces different bytes for the same value, so if the hash ever followed the
wire serialiser every stored digest would stop matching and dedup would silently fail
against existing datasets. These tests are the gate on that.
"""

import datetime
import decimal
import hashlib
import json
from unittest.mock import Mock

import pytest

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


def _legacy_digest(content: dict) -> str:
    """Exactly how the digest was produced before this change."""
    return hashlib.sha256(json.dumps(content, sort_keys=True).encode()).hexdigest()


@pytest.mark.parametrize("content", ITEM_SHAPES)
def test_content_hash__matches_the_legacy_digest(content):
    item = dataset_item.DatasetItem(**content)
    assert item.content_hash() == _legacy_digest(item.get_content())


@pytest.mark.parametrize("content", ITEM_SHAPES)
def test_content_hash__orjson_enabled__digest_is_unchanged(content, monkeypatch):
    """Enabling the fast wire serialiser must not move item identity."""
    pytest.importorskip("orjson")
    monkeypatch.setenv("OPIK_ENABLE_ORJSON_SERIALIZATION", "true")

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


@pytest.mark.parametrize("use_orjson", [True, False])
def test_insert__dedup_is_independent_of_the_wire_serialiser(use_orjson, monkeypatch):
    """The same duplicate is caught whichever serialiser writes the body."""
    monkeypatch.setenv(
        "OPIK_ENABLE_ORJSON_SERIALIZATION", "true" if use_orjson else "false"
    )
    capture = UploadCapture()
    dataset = make_dataset(Dataset, Mock(), capture)

    item = {"input": {"key": "value"}, "expected_output": {"key": "out"}}
    dataset.insert([item, item])

    assert len(capture.items) == 1, "The duplicate must be dropped either way"


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
def test_content_hash__ordinary_value__does_not_use_the_fallback(content, monkeypatch):
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


def test_content_hash__unhashable_value__still_raises():
    class NotSerializable:
        pass

    with pytest.raises(TypeError):
        dataset_item.DatasetItem(input=NotSerializable()).content_hash()
