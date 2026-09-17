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
import json
import logging
import pathlib
import uuid
from typing import Any

import pytest

from opik import json_helpers, jsonable_encoder
from opik.api_objects.experiment import bulk_converters, bulk_item
from opik.message_processing.batching import sequence_splitter
from opik.rest_api.core import datetime_utils

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
    """Force the standard-library branch. Runs everywhere.

    ``ACCELERATED`` is patched alongside the encoder because callers branch on the flag
    rather than on the private module; patching only one simulates a platform that
    cannot exist.
    """
    monkeypatch.setattr(json_helpers, "_orjson", None)
    monkeypatch.setattr(json_helpers, "ACCELERATED", False)


@pytest.fixture
def accelerated(monkeypatch):
    """Force the orjson branch, skipping where there is none to force."""
    if orjson is None:
        pytest.skip("orjson ships no wheel for this platform")
    monkeypatch.setattr(json_helpers, "_orjson", orjson)
    monkeypatch.setattr(json_helpers, "ACCELERATED", True)


def _rest_record(**kwargs: Any):
    kwargs.setdefault("dataset_item_id", "dataset-item-id")
    return bulk_converters.to_rest_record(bulk_item.ExperimentItemBulkRecord(**kwargs))


def _compact_bytes(rest_record: Any) -> int:
    """The record's JSON length, computed without going through the sizer.

    Compact separators and real UTF-8 are what both encoders write, so this is the
    number a correct measurement has to produce -- independent of the code under test,
    which is what makes it usable as an expected value rather than a restatement.
    """
    encoded = json.dumps(
        jsonable_encoder.encode(rest_record), separators=(",", ":"), ensure_ascii=False
    )
    return len(encoded.encode("utf-8"))


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


def test_payload_size_MB__accelerated__matches_the_structural_estimate_for_multibyte(
    accelerated,
):
    """The same agreement where the two could most plausibly drift apart.

    orjson writes non-ASCII as itself and the estimator counts the UTF-8 bytes, so a
    record of emoji and accents measures the same either way. Whether either matches
    the request body byte for byte is a separate question, and the same answer for
    both: httpx below 0.28 escapes non-ASCII on its way out, so both under-read such a
    record, exactly as they did before this changed which one runs.
    """
    record = _rest_record(
        trace=bulk_item.ExperimentItemBulkTrace(
            name="héllo 🙂 café",
            input={"q": "où est la bibliothèque 📚"},
            output={"a": "déjà vu 🎉 " * 20},
            start_time=START_TIME,
        )
    )

    assert bulk_converters.payload_size_MB(
        record
    ) == sequence_splitter.get_payload_size_MB(record)


def test_payload_size_MB__stdlib__falls_back_to_the_estimator(stdlib):
    """Without orjson this must not measure by encoding at all.

    ``json.dumps`` escapes non-ASCII where httpx does not, so it reads a multibyte record
    well over its wire size -- enough to split batches that would have fit. The estimator
    counts the characters themselves and does not have that problem.
    """
    record = _full_record()

    assert bulk_converters.payload_size_MB(
        record
    ) == sequence_splitter.get_payload_size_MB(record)


def test_payload_size_MB__stdlib__multibyte_is_not_inflated(stdlib):
    """The case that makes the fallback mandatory rather than merely tidy.

    ``json.dumps`` renders a non-ASCII character as a six-byte ``\\uXXXX`` escape, so
    measuring by encoding would report a record of emoji and accents far over its wire
    size. Falling back keeps the estimate on the characters themselves.
    """
    record = _rest_record(
        trace=bulk_item.ExperimentItemBulkTrace(
            name="héllo 🙂 café",
            input={"q": "où est la bibliothèque 📚"},
            output={"a": "déjà vu 🎉 " * 20},
            start_time=START_TIME,
        )
    )

    assert bulk_converters.payload_size_MB(
        record
    ) == sequence_splitter.get_payload_size_MB(record)


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
    "value,json_form",
    [
        pytest.param({"set": {1, 2, 3}}, {"set": [1, 2, 3]}, id="set"),
        pytest.param({"tuple": (1, 2)}, {"tuple": [1, 2]}, id="tuple"),
        pytest.param(
            {"decimal": decimal.Decimal("1.5")}, {"decimal": "1.5"}, id="decimal"
        ),
        pytest.param(
            {"uuid": uuid.UUID(int=1)},
            {"uuid": "00000000-0000-0000-0000-000000000001"},
            id="uuid",
        ),
        pytest.param(
            {"path": pathlib.PurePath("/tmp/x")}, {"path": "/tmp/x"}, id="path"
        ),
        pytest.param({"bytes": b"xy"}, {"bytes": "eHk="}, id="bytes"),
        pytest.param(
            {"date": datetime.date(2026, 8, 4)}, {"date": "2026-08-04"}, id="date"
        ),
        pytest.param({"time": datetime.time(1, 2, 3)}, {"time": "01:02:03"}, id="time"),
        pytest.param(
            {"nested": {"dt": START_TIME}},
            {"nested": {"dt": datetime_utils.serialize_datetime(START_TIME)}},
            id="nested-datetime",
        ),
    ],
)
def test_payload_size_MB__values_a_json_encoder_refuses__sized_as_their_json_form(
    mode, value, json_form, request
):
    """The upload accepts these, so measuring one must not be what rejects it.

    Asserting only that the number is positive would pass for any fallback that
    returned something, so what is pinned is the exact byte count of the form the
    value is actually sent as -- the second column, which is what the encoder these
    records go through renders each one to.
    """
    request.getfixturevalue(mode)

    expected_bytes = _compact_bytes(_rest_record(evaluate_task_result=json_form))

    assert bulk_converters.payload_size_MB(
        _rest_record(evaluate_task_result=value)
    ) == pytest.approx(expected_bytes / bulk_converters._BYTES_PER_MB, rel=1e-12)


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

    # Named rather than bare ``Exception``, which would let an unrelated failure stand
    # in for the refusal this test exists to provoke. Both encoders end here: orjson
    # gives up on the recursion and ``json_helpers`` re-encodes with the standard
    # library, which reports the cycle it found.
    with pytest.raises(ValueError, match="Circular reference"):
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


@pytest.mark.parametrize("mode", MODES)
def test_payload_size_MB__sizing_defect_after_encoding__propagates(
    mode, request, monkeypatch
):
    """A bug of ours must not arrive dressed as an unmeasurable record.

    The guard covers the encode, because that runs the caller's ``__str__``. Sizing the
    encoded result runs no caller code, so anything raising there is the SDK's own
    defect -- and absorbing it would report the caller's data as the problem while the
    real cause disappeared.
    """
    request.getfixturevalue(mode)

    def broken(_encoded):
        raise AttributeError("sizing is broken")

    monkeypatch.setattr(sequence_splitter, "get_encoded_payload_size_MB", broken)
    record = _rest_record(evaluate_task_result={"a": "b"})

    # Forces the estimator rather than the encoder-measured path, which does not
    # reach the estimate at all for a record orjson can encode.
    monkeypatch.setattr(json_helpers, "ACCELERATED", False)

    with pytest.raises(AttributeError, match="sizing is broken"):
        bulk_converters.payload_size_MB(record)


@pytest.mark.parametrize("mode", MODES)
def test_payload_size_MB__value_whose_str_raises__reports_the_cause(
    mode, request, caplog, monkeypatch
):
    """The one value the fallback cannot absorb, because the fallback is what breaks.

    ``jsonable_encoder.encode`` ends in ``str(obj)``, outside its own ``try``, so an
    object that refuses to render a string escapes it. Both the encoder and the
    structural estimate go through it, so retrying the estimate raises the same
    exception again and there is no size to return.
    """
    request.getfixturevalue(mode)

    class Hostile:
        def __str__(self) -> str:
            raise RuntimeError("no string for you")

        __repr__ = __str__

    record = _rest_record(evaluate_task_result={"h": Hostile()})

    # The SDK's own root logger sets propagate=False, which is where caplog loses the
    # record -- this logger is a child of it and propagates fine on its own.
    monkeypatch.setattr(logging.getLogger("opik"), "propagate", True)

    with caplog.at_level(logging.WARNING, logger=bulk_converters.LOGGER.name):
        with pytest.raises(bulk_converters.UnmeasurableRecordError) as raised:
            bulk_converters.payload_size_MB(record)

    # The type travels on the exception, so the caller can say what happened rather
    # than inventing a size; the traceback that names the value is in the log.
    assert isinstance(raised.value.cause, RuntimeError)
    assert "no string for you" in caplog.text
