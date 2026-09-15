"""Both encoder modes, pinned, whichever encoder this machine happens to have.

`json_helpers` picks orjson at import time where a wheel exists. Tests that simply call
it would therefore assert one thing on a developer's laptop and another on a platform
with no wheel, which is the opposite of coverage. Every test here forces the mode it
means to exercise, so both branches run everywhere.
"""

import datetime
import importlib
import json
import sys

import pytest

from opik import json_helpers

try:
    import orjson
except (
    ImportError
):  # no wheel for this platform; only the standard-library branch exists
    orjson = None

# Deliberately NOT a module-level skip. The standard-library assertions are worth most
# on a platform with no orjson wheel, because there the standard library is the only
# encoder there is -- skipping the whole module when orjson is missing would drop that
# coverage exactly where it matters. Only the accelerated half stands down.
requires_orjson = pytest.mark.skipif(
    orjson is None, reason="orjson ships no wheel for this platform"
)


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
@requires_orjson
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


# --------------------------------------------------------------------------- #
# import-time state, which the fixtures above deliberately cannot reach
# --------------------------------------------------------------------------- #
# The fixtures swap `_orjson` on an already-imported module, which exercises `dumps`
# but leaves `ACCELERATED` at whatever import decided. Reloading under a blocked import
# is the only way to run the `except ImportError` branch itself.
def _with_orjson_unavailable(probe):
    """Reload json_helpers with `import orjson` failing, run `probe` on it, restore.

    `probe` runs while the module is still in its fallback state and its result is what
    comes back: `importlib.reload` mutates the module in place and hands back the same
    object, so returning the module itself would hand the caller something the restore
    below has already put right again.
    """

    class Blocked:
        def find_spec(self, name, path=None, target=None):
            if name == "orjson":
                raise ImportError("no orjson wheel for this platform")
            return None

    blocker = Blocked()
    sys.meta_path.insert(0, blocker)
    saved = sys.modules.pop("orjson", None)
    try:
        return probe(importlib.reload(json_helpers))
    finally:
        sys.meta_path.remove(blocker)
        if saved is not None:
            sys.modules["orjson"] = saved
        importlib.reload(json_helpers)


@requires_orjson
def test_import__orjson_present__accelerated_is_true():
    assert json_helpers.ACCELERATED is True
    assert json_helpers._orjson is not None


def test_import__orjson_unavailable__falls_back_at_import_time():
    """The `except ImportError` branch, run for real rather than simulated."""
    accelerated, encoder = _with_orjson_unavailable(
        lambda module: (module.ACCELERATED, module._orjson)
    )

    assert accelerated is False
    assert encoder is None


def test_import__orjson_unavailable__still_encodes():
    """A platform with no wheel gets a working encoder, not a broken import."""
    encoded = _with_orjson_unavailable(
        lambda module: module.dumps({"b": 1, "a": 2}, sort_keys=True)
    )

    assert json.loads(encoded) == {"a": 2, "b": 1}


@requires_orjson
def test_import__restored_afterwards():
    """The helper must leave the module as it found it, or every later test lies."""
    _with_orjson_unavailable(lambda module: None)

    assert json_helpers.ACCELERATED is True
    assert json_helpers._orjson is not None


# --------------------------------------------------------------------------- #
# builtin subclasses, which `default` has no case for
# --------------------------------------------------------------------------- #
class _Str(str):
    pass


class _Int(int):
    pass


class _List(list):
    pass


class _Dict(dict):
    pass


SUBCLASSES = [
    pytest.param({"v": _Str("hello")}, {"v": "hello"}, id="str-subclass"),
    pytest.param({"v": _Int(7)}, {"v": 7}, id="int-subclass"),
    pytest.param({"v": _List([1, 2])}, {"v": [1, 2]}, id="list-subclass"),
    pytest.param({"v": _Dict({"a": 1})}, {"v": {"a": 1}}, id="dict-subclass"),
]


@pytest.mark.parametrize("value, expected", SUBCLASSES)
@pytest.mark.parametrize("mode", ["stdlib", "accelerated"])
def test_dumps__builtin_subclass__serialises_as_its_builtin(
    value, expected, mode, request
):
    """A subclass of str/int/list/dict must encode as the builtin it derives from.

    `OPT_PASSTHROUGH_SUBCLASS` would route these to `default`, which has no case for
    them and raises -- failing an upload the standard library accepts. This is the
    assertion that keeps that option off.
    """
    request.getfixturevalue(mode)

    assert json.loads(json_helpers.dumps(value, default=flexible)) == expected


@pytest.mark.parametrize("value, expected", SUBCLASSES)
def test_dumps__builtin_subclass__default_is_never_consulted(
    value, expected, accelerated
):
    """Both encoders handle these natively; reaching `default` at all is the bug."""

    def explode(unencodable):
        raise AssertionError(
            f"`default` must not see {type(unencodable).__name__}; it has no case for it"
        )

    assert json.loads(json_helpers.dumps(value, default=explode)) == expected
