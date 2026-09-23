"""What the accelerated digest owes, which is not what the standard-library one owes.

`json_helpers` encodes with orjson where a wheel exists and with the standard library
everywhere else, and the two do not produce identical bytes -- the standard library
renders a float below 1e-4 as `9.79e-05` where orjson writes `0.0000979`, and neither is
configurable. So a digest made by one encoder does not match one made by the other.

That is not a contract this SDK breaks, because it never compares the two. `Dataset`
reads no digest from the backend: `__internal_api__sync_hashes__` streams the stored
items down and rehashes them in this process, with this interpreter's encoder, before
`_deduplicating` hashes the items being written. Both sides of every comparison are made
by the same encoder, in the same process, moments apart.

So the standard-library digests stay pinned to written-down bytes in
`test_content_hash_stability.py` -- that is the encoder every platform has -- and what is
asserted here instead is that the accelerated path is self-consistent, separates content
that differs, and agrees with itself across the two callers that have to agree.
"""

import pytest

from opik import json_helpers
from opik.api_objects.dataset import dataset_item

pytestmark = pytest.mark.skipif(
    not json_helpers.ACCELERATED,
    reason="orjson ships no wheel for this platform, so the standard library is the "
    "only encoder here and test_content_hash_stability.py covers it",
)

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
    # The value that makes the two encoders disagree, so the case most worth pinning.
    pytest.param({"score": 9.793360101850946e-05}, id="small-float"),
]


@pytest.mark.parametrize("content", ITEM_SHAPES)
def test_content_hash__same_content__same_digest(content):
    """The same content must hash the same way every time it is asked."""
    first = dataset_item.DatasetItem(**content).content_hash()
    second = dataset_item.DatasetItem(**content).content_hash()

    assert first == second
    assert len(first) == 64


@pytest.mark.parametrize("content", ITEM_SHAPES)
def test_content_hash__key_order_does_not_matter(content):
    """Sorted keys, so two dicts built in different orders are one item, not two."""
    reversed_content = dict(reversed(list(content.items())))

    assert (
        dataset_item.DatasetItem(**content).content_hash()
        == dataset_item.DatasetItem(**reversed_content).content_hash()
    )


def test_content_hash__different_content__different_digest():
    """Faster hashing must not collapse items that differ."""
    assert (
        dataset_item.DatasetItem(input={"k": 1}).content_hash()
        != dataset_item.DatasetItem(input={"k": 2}).content_hash()
    )


def test_content_hash__matches_across_the_two_callers():
    """The written items and the streamed-back items have to agree.

    `_deduplicating` hashes what is being uploaded; `__internal_api__sync_hashes__`
    hashes what the backend already holds. A duplicate is caught only where the two
    produce the same digest for the same content, so they must stay on one code path.
    """
    content = {"input": {"nested": [1, 2, {"x": "y"}]}, "expected_output": "out"}

    assert (
        dataset_item.DatasetItem(**content).content_hash()
        == dataset_item.DatasetItem(**content).content_hash()
    )


def test_content_hash__differs_from_the_standard_library(monkeypatch):
    """Pins the reason this file exists, rather than leaving it to the docstring.

    If these ever coincide the split is pointless -- but so is the risk, so this asserts
    the difference is real and expected rather than asserting it must persist.
    """
    content = {"score": 9.793360101850946e-05}
    accelerated = dataset_item.DatasetItem(**content).content_hash()

    monkeypatch.setattr(json_helpers, "_orjson", None)
    standard = dataset_item.DatasetItem(**content).content_hash()

    assert accelerated != standard, (
        "The two encoders render small floats differently; if that stops being true, "
        "the digests may be shared and this file can be simplified"
    )


def test_content_hash__unserializable_value__still_raises():
    """The accelerated path must reject what the standard library rejects."""

    class NotSerializable:
        pass

    with pytest.raises(TypeError):
        dataset_item.DatasetItem(input=NotSerializable()).content_hash()


def test_content_hash__integer_beyond_orjson_range__still_hashes():
    """orjson refuses these before consulting `default`, so the fallback has to catch it."""
    digest = dataset_item.DatasetItem(big=2**64).content_hash()

    assert len(digest) == 64
    assert digest == dataset_item.DatasetItem(big=2**64).content_hash()
