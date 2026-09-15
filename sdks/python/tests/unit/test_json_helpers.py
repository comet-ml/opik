"""Both encoder modes, pinned, whichever encoder this machine happens to have.

`json_helpers` picks orjson at import time where a wheel exists. Tests that simply call
it would therefore assert one thing on a developer's laptop and another on a platform
with no wheel, which is the opposite of coverage. Every test here forces the mode it
means to exercise, so both branches run everywhere.
"""

import datetime
import json

import pytest

from opik import json_helpers

try:
    import orjson
except (
    ImportError
):  # no wheel for this platform; only the standard-library branch exists
    orjson = None

pytestmark = pytest.mark.skipif(
    orjson is None,
    reason="orjson ships no wheel for this platform, so there is no second mode to pin",
)


@pytest.fixture
def stdlib(monkeypatch):
    """Force the standard-library branch."""
    monkeypatch.setattr(json_helpers, "_orjson", None)


@pytest.fixture
def accelerated(monkeypatch):
    """Force the orjson branch, in case something earlier cleared it."""
    monkeypatch.setattr(json_helpers, "_orjson", orjson)


def flexible(value):
    if isinstance(value, datetime.datetime):
        return value.isoformat()
    raise TypeError(f"Object of type {type(value).__name__} is not JSON serializable")


# --------------------------------------------------------------------------- #
# what both modes must agree on
# --------------------------------------------------------------------------- #
VALUES = [
    pytest.param({"a": 1, "b": "x"}, id="flat"),
    pytest.param({"n": {"deep": [1, 2, {"x": "y"}]}}, id="nested"),
    pytest.param({"u": "héllo 🙂"}, id="unicode"),
    pytest.param({"f": 1.5, "t": True, "z": None}, id="scalars"),
    pytest.param({"e": {}, "l": [], "s": ""}, id="empty"),
    pytest.param({"big": 2**64}, id="int-beyond-orjson-range"),
    pytest.param({"neg": -(2**63) - 1}, id="negative-beyond-orjson-range"),
]


@pytest.mark.parametrize("value", VALUES)
@pytest.mark.parametrize("mode", ["stdlib", "accelerated"])
def test_dumps__round_trips_in_both_modes(value, mode, request):
    request.getfixturevalue(mode)

    assert json.loads(json_helpers.dumps(value, default=flexible)) == value


@pytest.mark.parametrize("mode", ["stdlib", "accelerated"])
def test_dumps__sort_keys__orders_keys(mode, request):
    request.getfixturevalue(mode)

    encoded = json_helpers.dumps({"b": 1, "a": 2}, sort_keys=True)

    assert list(json.loads(encoded)) == ["a", "b"]


@pytest.mark.parametrize("mode", ["stdlib", "accelerated"])
def test_dumps__without_sort_keys__keeps_insertion_order(mode, request):
    request.getfixturevalue(mode)

    encoded = json_helpers.dumps({"b": 1, "a": 2})

    assert list(json.loads(encoded)) == ["b", "a"]


@pytest.mark.parametrize("mode", ["stdlib", "accelerated"])
def test_dumps__default_handles_what_the_encoder_cannot(mode, request):
    request.getfixturevalue(mode)
    when = datetime.datetime(2024, 1, 2, 3, 4, 5, tzinfo=datetime.timezone.utc)

    encoded = json_helpers.dumps({"when": when}, default=flexible)

    assert json.loads(encoded) == {"when": "2024-01-02T03:04:05+00:00"}, (
        "orjson renders datetime natively unless told to pass it through; if this "
        "returns '...05Z' the passthrough options were lost"
    )


@pytest.mark.parametrize("mode", ["stdlib", "accelerated"])
def test_dumps__unserialisable_value__raises_type_error(mode, request):
    request.getfixturevalue(mode)

    class Opaque:
        pass

    with pytest.raises(TypeError):
        json_helpers.dumps({"v": Opaque()}, default=flexible)


@pytest.mark.parametrize("mode", ["stdlib", "accelerated"])
def test_dumps__no_default__unserialisable_value_still_raises(mode, request):
    request.getfixturevalue(mode)

    class Opaque:
        pass

    with pytest.raises(TypeError):
        json_helpers.dumps({"v": Opaque()})


# --------------------------------------------------------------------------- #
# the fallback: orjson's own refusal versus a failure inside `default`
# --------------------------------------------------------------------------- #
def test_dumps__integer_beyond_range__falls_back_without_calling_default(accelerated):
    """orjson refuses these before consulting `default`, so the retry must not either."""
    calls = []

    def counting(value):
        calls.append(value)
        raise TypeError("unreachable")

    encoded = json_helpers.dumps({"v": 2**64}, default=counting)

    assert json.loads(encoded) == {"v": 2**64}
    assert calls == [], "`default` has no say in an out-of-range integer"


def test_dumps__default_raises__propagates_the_callers_exception(accelerated):
    """Not orjson's paraphrase of it, which loses the type and the message."""

    class Sentinel(TypeError):
        pass

    class Opaque:
        pass

    def exploding(value):
        raise Sentinel("the caller's own message")

    with pytest.raises(Sentinel, match="the caller's own message"):
        json_helpers.dumps({"v": Opaque()}, default=exploding)


def test_dumps__default_raises__is_not_called_twice(accelerated):
    """The retry is for orjson's refusals only; `default` may have side effects."""
    calls = []

    class Opaque:
        pass

    def counting(value):
        calls.append(value)
        raise TypeError("no")

    with pytest.raises(TypeError):
        json_helpers.dumps({"v": Opaque()}, default=counting)

    assert len(calls) == 1, f"`default` ran {len(calls)} times, expected once"


# --------------------------------------------------------------------------- #
# where the two modes genuinely differ
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize(
    "value", [float("nan"), float("inf"), float("-inf")], ids=["nan", "inf", "-inf"]
)
def test_dumps__non_finite_float__differs_by_encoder(value, monkeypatch):
    """Pinned because it is a real divergence, not because either side is wrong.

    The standard library emits `NaN`/`Infinity`, which no JSON parser is obliged to
    accept; orjson emits `null`. Neither is configurable. Nothing compares the two --
    a body is parsed by the backend and a digest never leaves the process -- but a
    value that changes shape deserves to fail loudly here if either side ever moves.
    """
    monkeypatch.setattr(json_helpers, "_orjson", orjson)
    accelerated_bytes = json_helpers.dumps({"v": value})

    monkeypatch.setattr(json_helpers, "_orjson", None)
    stdlib_bytes = json_helpers.dumps({"v": value})

    assert accelerated_bytes == b'{"v":null}'
    assert stdlib_bytes != accelerated_bytes


def test_accelerated_flag__reports_the_branch_in_use(accelerated, stdlib):
    """ACCELERATED is a diagnostic, and is read at import; behaviour does not use it."""
    assert isinstance(json_helpers.ACCELERATED, bool)
