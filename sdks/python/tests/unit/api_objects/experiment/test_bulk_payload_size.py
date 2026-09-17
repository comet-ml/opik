"""Sizing a converted bulk record, in both encoder modes.

``json_helpers`` picks orjson at import time where a wheel exists, so a test that just
called the sizer would assert one thing on a laptop and another on a platform without
one. Every test here forces the mode it means to exercise, so both branches run
everywhere. The mode is forced on ``json_helpers`` itself because that is the only place
in the SDK that knows which encoder is in use.
"""

import datetime
import decimal
import enum
import pathlib
import uuid
from typing import Any

import pytest

from opik import json_helpers
from opik.api_objects.experiment import bulk_converters, bulk_item
from opik.message_processing.batching import sequence_splitter

try:
    import orjson
except (
    ImportError
):  # no wheel for this platform; only the standard-library branch exists
    orjson = None

START_TIME = datetime.datetime(2026, 8, 4, 12, 0, 0)

MODES = [
    pytest.param("stdlib", id="stdlib"),
    pytest.param("accelerated", id="accelerated"),
]


@pytest.fixture
def stdlib(monkeypatch):
    """Force the standard-library branch. Runs everywhere."""
    monkeypatch.setattr(json_helpers, "_orjson", None)


@pytest.fixture
def accelerated(monkeypatch):
    """Force the orjson branch, skipping where there is none to force."""
    if orjson is None:
        pytest.skip("orjson ships no wheel for this platform")
    monkeypatch.setattr(json_helpers, "_orjson", orjson)


def _rest_record(**kwargs: Any):
    kwargs.setdefault("dataset_item_id", "dataset-item-id")
    return bulk_converters.to_rest_record(bulk_item.ExperimentItemBulkRecord(**kwargs))


def _full_record():
    return _rest_record(
        trace=bulk_item.ExperimentItemBulkTrace(
            id="trace-id",
            name="eval_task",
            start_time=START_TIME,
            end_time=START_TIME + datetime.timedelta(seconds=1),
            input={"question": "q" * 200, "context": ["c" * 100]},
            output={"answer": "a" * 200},
            metadata={"model": "gpt-4o", "temperature": 0.0},
            tags=["experiment"],
        ),
        spans=[
            bulk_item.ExperimentItemBulkSpan(
                id="span-id",
                name="llm",
                type="llm",
                start_time=START_TIME,
                end_time=START_TIME + datetime.timedelta(seconds=1),
                input={"messages": [{"role": "user", "content": "m" * 150}]},
                output={"choices": [{"content": "o" * 150}]},
                usage={"prompt_tokens": 100},
                total_estimated_cost=0.0012,
            )
        ],
        feedback_scores=[{"name": "relevance", "value": 1.0}],
    )


def test_payload_size_MB__accelerated__matches_the_structural_estimate(accelerated):
    """The estimate this replaces is what every batch boundary was tuned against.

    orjson writes compact JSON, which is the shape the structural estimator adds up, so
    on this path the two agree to the byte and batching does not move at all.
    """
    record = _full_record()

    assert bulk_converters.payload_size_MB(record) == pytest.approx(
        sequence_splitter.get_payload_size_MB(record), rel=1e-9
    )


def test_payload_size_MB__stdlib__never_under_estimates(stdlib):
    """``json.dumps`` writes ``", "`` and ``": "`` where orjson writes ``,`` and ``:``.

    The extra byte per separator makes this estimate a few percent high, which closes a
    batch marginally early -- the safe direction, and the reason the bound is one-sided.
    Tightening it would mean compact separators in ``json_helpers``, which would change
    the bytes the dataset path puts on the wire.
    """
    record = _full_record()
    structural = sequence_splitter.get_payload_size_MB(record)

    assert structural <= bulk_converters.payload_size_MB(record) < structural * 1.2


@pytest.mark.parametrize("mode", MODES)
def test_payload_size_MB__grows_with_the_record(mode, request):
    request.getfixturevalue(mode)

    small = _rest_record(
        trace=bulk_item.ExperimentItemBulkTrace(
            start_time=START_TIME, output={"a": "x"}
        )
    )
    large = _rest_record(
        trace=bulk_item.ExperimentItemBulkTrace(
            start_time=START_TIME, output={"a": "x" * 2_000_000}
        )
    )

    assert bulk_converters.payload_size_MB(large) > 1.0
    assert bulk_converters.payload_size_MB(small) < 0.001


@pytest.mark.parametrize("mode", MODES)
@pytest.mark.parametrize(
    "value",
    [
        pytest.param({"set": {1, 2, 3}}, id="set"),
        pytest.param({"tuple": (1, 2)}, id="tuple"),
        pytest.param({"decimal": decimal.Decimal("1.5")}, id="decimal"),
        pytest.param({"uuid": uuid.UUID(int=1)}, id="uuid"),
        pytest.param({"path": pathlib.PurePath("/tmp/x")}, id="path"),
        pytest.param({"bytes": b"xy"}, id="bytes"),
        pytest.param({"date": datetime.date(2026, 8, 4)}, id="date"),
        pytest.param({"time": datetime.time(1, 2, 3)}, id="time"),
        pytest.param({"nested": {"dt": START_TIME}}, id="nested-datetime"),
    ],
)
def test_payload_size_MB__values_a_json_encoder_refuses__still_sized(
    mode, value, request
):
    """The upload accepts these, so measuring one must not be what rejects it."""
    request.getfixturevalue(mode)

    assert bulk_converters.payload_size_MB(_rest_record(evaluate_task_result=value)) > 0


@pytest.mark.parametrize("mode", MODES)
def test_payload_size_MB__unencodable_value__falls_back_to_the_estimate(mode, request):
    """A cycle is the one shape neither encoder can walk at all.

    The structural estimate reaches one too, by recursing until Python stops it, so what
    is pinned here is that the fallback is taken -- not the number it returns, which is
    not stable for a cyclic value and was not before this change either.
    """
    request.getfixturevalue(mode)

    cyclic: dict = {}
    cyclic["self"] = cyclic
    record = _rest_record(evaluate_task_result={"cyclic": cyclic})

    with pytest.raises(Exception):
        json_helpers.dumps(record, default=bulk_converters._json_shell, sort_keys=False)

    assert bulk_converters.payload_size_MB(record) > 0


@pytest.mark.parametrize("mode", MODES)
def test_payload_size_MB__arbitrary_object__sized_rather_than_raising(mode, request):
    request.getfixturevalue(mode)

    class Opaque:
        def __repr__(self) -> str:
            return "<opaque>"

    assert (
        bulk_converters.payload_size_MB(
            _rest_record(evaluate_task_result={"o": Opaque()})
        )
        > 0
    )


@pytest.mark.parametrize("mode", MODES)
def test_payload_size_MB__enum_member__sized_by_its_value(mode, request):
    """orjson renders an Enum natively, the standard library cannot -- same size either way."""
    request.getfixturevalue(mode)

    class Colour(enum.Enum):
        RED = "red"

    by_member = bulk_converters.payload_size_MB(
        _rest_record(evaluate_task_result={"c": Colour.RED})
    )
    by_value = bulk_converters.payload_size_MB(
        _rest_record(evaluate_task_result={"c": "red"})
    )

    assert by_member == by_value


def test_payload_size_MB__integer_beyond_orjson_range__sized_by_the_fallback(
    accelerated,
):
    """orjson refuses these outright; ``json_helpers`` re-encodes with the standard
    library, and the default has to keep working there too."""
    record = _rest_record(
        trace=bulk_item.ExperimentItemBulkTrace(
            start_time=START_TIME, output={"big": 2**70}
        )
    )

    assert bulk_converters.payload_size_MB(record) > 0
