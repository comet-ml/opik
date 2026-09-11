"""Item identity must not move when the wire serialiser changes.

`content_hash` feeds duplicate detection, and the digests of items already stored were
produced by `json.dumps(..., sort_keys=True)` with default separators. A compact
serialiser produces different bytes for the same value, so if the hash ever followed the
wire serialiser every stored digest would stop matching and dedup would silently fail
against existing datasets. These tests are the gate on that.
"""

import hashlib
import json
from unittest.mock import Mock

import pytest

from opik.api_objects.dataset import dataset_item
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
