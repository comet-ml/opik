"""Serialising and sizing a converted bulk record, in both encoder modes.

The size is the length of the bytes that will be sent, so these tests are about the
request body as much as about the number: the reference value throughout is the
generated client's own wire form, re-encoded.

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

from opik import json_helpers
from opik.api_objects.experiment import bulk_converters, bulk_item
from opik.rest_api.core import datetime_utils
from opik.rest_api.core.jsonable_encoder import jsonable_encoder

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
    """The generated client's wire form, re-encoded the way orjson writes it.

    ``jsonable_encoder`` here is the generated client's own, which is what built the
    request body before this changed -- so this is the expected value rather than a
    restatement of the code under test. Compact separators and real UTF-8 are what
    orjson writes.
    """
    encoded = json.dumps(
        jsonable_encoder(rest_record), separators=(",", ":"), ensure_ascii=False
    )
    return len(encoded.encode("utf-8"))


def _stdlib_bytes(rest_record: Any) -> int:
    """The same wire form, re-encoded the way the standard library writes it.

    ``json.dumps`` defaults -- ``", "`` and ``": "`` separators, non-ASCII escaped --
    which is what ``json_helpers`` produces where there is no orjson wheel.
    """
    return len(json.dumps(jsonable_encoder(rest_record)).encode("utf-8"))


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


def test_payload_size_MB__accelerated__matches_the_generated_request_body(accelerated):
    """The number is the length of the body, and the body is what the client sent before.

    orjson writes compact JSON with real UTF-8, so measuring the bytes this path produces
    has to agree exactly with re-encoding the generated client's own wire form the same
    way. That is the parity check in its smallest form: same keys, same values, same
    omissions, therefore same length.
    """
    record = _full_record()

    assert bulk_converters.payload_size_MB(record) == pytest.approx(
        _compact_bytes(record) / bulk_converters._BYTES_PER_MB, rel=1e-12
    )


def test_payload_size_MB__accelerated__matches_the_generated_body_for_multibyte(
    accelerated,
):
    """The same agreement where the two could most plausibly drift apart.

    orjson writes non-ASCII as itself, so a record of emoji and accents measures its
    UTF-8 length. The standard library would escape each one instead, which is the case
    below rather than a disagreement about what the record contains.
    """
    record = _rest_record(
        trace=bulk_item.ExperimentItemBulkTrace(
            name="héllo 🙂 café",
            input={"q": "où est la bibliothèque 📚"},
            output={"a": "déjà vu 🎉 " * 20},
            start_time=START_TIME,
        )
    )

    assert bulk_converters.payload_size_MB(record) == pytest.approx(
        _compact_bytes(record) / bulk_converters._BYTES_PER_MB, rel=1e-12
    )


def test_payload_size_MB__stdlib__measures_the_body_it_will_send(stdlib):
    """Without orjson the body is the standard library's, so the size is too.

    The two encoders write the same JSON in different bytes -- ``json.dumps`` separates
    with ``", "`` and escapes non-ASCII -- and this path sends whichever one answered.
    Measuring the other one's output would be a prediction again, and wrong in the
    direction that matters: it would under-read the body actually being sent and build
    batches the server then rejects.
    """
    record = _full_record()

    assert bulk_converters.payload_size_MB(record) == pytest.approx(
        _stdlib_bytes(record) / bulk_converters._BYTES_PER_MB, rel=1e-12
    )


def test_payload_size_MB__stdlib__multibyte_is_measured_as_it_is_escaped(stdlib):
    """The case that makes measuring, rather than estimating, the right answer.

    ``json.dumps`` renders a non-ASCII character as a six-byte ``\\uXXXX`` escape, so a
    record of emoji and accents really does go out far larger than its characters
    suggest. The number says so, which is what keeps a batch of them inside the
    per-request cap.
    """
    record = _rest_record(
        trace=bulk_item.ExperimentItemBulkTrace(
            name="héllo 🙂 café",
            input={"q": "où est la bibliothèque 📚"},
            output={"a": "déjà vu 🎉 " * 20},
            start_time=START_TIME,
        )
    )

    assert bulk_converters.payload_size_MB(record) == pytest.approx(
        _stdlib_bytes(record) / bulk_converters._BYTES_PER_MB, rel=1e-12
    )
    assert (
        bulk_converters.payload_size_MB(record)
        > _compact_bytes(record) / bulk_converters._BYTES_PER_MB
    )


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
def test_payload_size_MB__values_a_json_encoder_refuses__sent_as_their_json_form(
    mode, value, json_form, request
):
    """The upload accepted these, so serialising one must not be what rejects it.

    What is pinned is the exact form each value goes out as -- the second column, which
    is what the generated client rendered it to -- asserted on the bytes rather than on
    the size alone, so a shape that merely happened to be the same length cannot pass.
    """
    request.getfixturevalue(mode)

    sent = json.loads(
        bulk_converters.serialize_record(_rest_record(evaluate_task_result=value))
    )

    assert sent["evaluate_task_result"] == json_form


@pytest.mark.parametrize("mode", MODES)
def test_payload_size_MB__unencodable_value__is_refused(mode, request):
    """A cycle is the one shape neither encoder can walk at all.

    There is nothing to fall back to now that the measurement is the body: a record that
    cannot be serialised cannot be sent either, so it is refused rather than given a
    number that would read as an oversized record.
    """
    request.getfixturevalue(mode)

    cyclic: dict = {}
    cyclic["self"] = cyclic
    record = _rest_record(evaluate_task_result={"cyclic": cyclic})

    with pytest.raises(bulk_converters.UnmeasurableRecordError):
        bulk_converters.payload_size_MB(record)


@pytest.mark.parametrize("mode", MODES)
def test_payload_size_MB__arbitrary_object__is_refused_rather_than_degraded(
    mode, request
):
    """The one deliberate change of contract, pinned so it cannot happen by accident.

    The generated client's last resort was ``vars(obj)``, which uploaded an object as a
    dict of its attributes -- a silent, lossy success. The shared encoder refuses it
    instead and names the type, so the caller finds out here rather than in the stored
    data. Mirrors ``ItemNotSerializableError`` on the dataset path.
    """
    request.getfixturevalue(mode)

    class Opaque:
        def __init__(self) -> None:
            self.field = "value"

    with pytest.raises(bulk_converters.UnmeasurableRecordError) as raised:
        bulk_converters.payload_size_MB(
            _rest_record(evaluate_task_result={"o": Opaque()})
        )

    assert isinstance(raised.value.cause, TypeError)
    assert "Opaque" in str(raised.value.cause)


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
    assert json.loads(bulk_converters.serialize_record(record))["trace"]["output"] == {
        "big": 2**70
    }


@pytest.mark.parametrize("mode", MODES)
def test_payload_size_MB__value_whose_str_raises__is_refused_without_calling_it(
    mode, request, caplog, monkeypatch
):
    """Refusing must not itself run the caller's code.

    The old fallback ended in ``str(obj)``, so a value that refused to render one turned
    a refusal into that object's own exception. The shared encoder names the type
    instead, which reaches no caller code at all, so the failure is the same whatever the
    value does when asked to render.
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

    assert isinstance(raised.value.cause, TypeError)
    assert "Hostile" in str(raised.value.cause)
    assert "Could not serialize an experiment item" in caplog.text
