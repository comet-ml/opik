"""Both encoder modes, pinned, whichever encoder this machine happens to have.

`json_helpers` picks orjson at import time where a wheel exists. Tests that simply call
it would therefore assert one thing on a developer's laptop and another on a platform
with no wheel, which is the opposite of coverage. Every test here forces the mode it
means to exercise, so both branches run everywhere.
"""

import datetime
import enum
import importlib
import json
import sys
import uuid

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

    assert (
        json.loads(json_helpers.dumps(value, default=flexible, sort_keys=False))
        == value
    )


@pytest.mark.parametrize("mode", ["stdlib", "accelerated"])
def test_dumps__sort_keys__orders_keys(mode, request):
    request.getfixturevalue(mode)

    encoded = json_helpers.dumps({"b": 1, "a": 2}, default=None, sort_keys=True)

    assert list(json.loads(encoded)) == ["a", "b"]


@pytest.mark.parametrize("mode", ["stdlib", "accelerated"])
def test_dumps__without_sort_keys__keeps_insertion_order(mode, request):
    request.getfixturevalue(mode)

    encoded = json_helpers.dumps({"b": 1, "a": 2}, default=None, sort_keys=False)

    assert list(json.loads(encoded)) == ["b", "a"]


@pytest.mark.parametrize("mode", ["stdlib", "accelerated"])
def test_dumps__default_handles_what_the_encoder_cannot(mode, request):
    request.getfixturevalue(mode)
    when = datetime.datetime(2024, 1, 2, 3, 4, 5, tzinfo=datetime.timezone.utc)

    encoded = json_helpers.dumps({"when": when}, default=flexible, sort_keys=False)

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
        json_helpers.dumps({"v": Opaque()}, default=flexible, sort_keys=False)


@pytest.mark.parametrize("mode", ["stdlib", "accelerated"])
def test_dumps__no_default__unserialisable_value_still_raises(mode, request):
    request.getfixturevalue(mode)

    class Opaque:
        pass

    with pytest.raises(TypeError):
        json_helpers.dumps({"v": Opaque()}, default=None, sort_keys=False)


# --------------------------------------------------------------------------- #
# the fallback: orjson's own refusal versus a failure inside `default`
# --------------------------------------------------------------------------- #
def test_dumps__integer_beyond_range__falls_back_without_calling_default(accelerated):
    """orjson refuses these before consulting `default`, so the retry must not either."""
    calls = []

    def counting(value):
        calls.append(value)
        raise TypeError("unreachable")

    encoded = json_helpers.dumps({"v": 2**64}, default=counting, sort_keys=False)

    assert json.loads(encoded) == {"v": 2**64}
    assert calls == [], "`default` has no say in an out-of-range integer"


@pytest.mark.parametrize("mode", ["stdlib", "accelerated"])
@pytest.mark.parametrize("sort_keys", [False, True])
@pytest.mark.parametrize("number", [42, 2**64, -(2**63) - 1])
@pytest.mark.parametrize("position", ["before", "between", "after", "converted"])
def test_dumps__integer_fallback__preserves_each_default_occurrence(
    mode, sort_keys, number, position, request
):
    request.getfixturevalue(mode)
    calls = []
    opaque = object()

    def counting(value):
        assert value is opaque
        calls.append(value)
        result = {"serial": len(calls)}
        if position == "converted":
            result["number"] = number
        return result

    if position == "before":
        value = [number, opaque, opaque]
        expected = [number, {"serial": 1}, {"serial": 2}]
    elif position == "between":
        value = [opaque, number, opaque]
        expected = [{"serial": 1}, number, {"serial": 2}]
    elif position == "after":
        value = [opaque, opaque, number]
        expected = [{"serial": 1}, {"serial": 2}, number]
    else:
        value = [opaque, opaque]
        expected = [{"serial": 1, "number": number}, {"serial": 2, "number": number}]

    encoded = json_helpers.dumps(value, default=counting, sort_keys=sort_keys)

    assert json.loads(encoded) == expected
    assert len(calls) == 2


@pytest.mark.parametrize("mode", ["stdlib", "accelerated"])
def test_dumps__integer_fallback__handles_defaults_only_stdlib_visits(mode, request):
    request.getfixturevalue(mode)
    opaque = object()
    identifier = uuid.UUID("12345678-1234-5678-1234-567812345678")

    class Choice(enum.Enum):
        FIRST = "first"

    calls = []

    def counting(value):
        calls.append(value)
        if value is opaque:
            return {"serial": sum(item is opaque for item in calls)}
        if isinstance(value, uuid.UUID):
            return str(value)
        if isinstance(value, enum.Enum):
            return value.value
        raise TypeError("unsupported")

    encoded = json_helpers.dumps(
        [identifier, Choice.FIRST, opaque, 2**64, opaque],
        default=counting,
        sort_keys=False,
    )

    assert json.loads(encoded) == [
        str(identifier),
        "first",
        {"serial": 1},
        2**64,
        {"serial": 2},
    ]
    assert sum(item is opaque for item in calls) == 2
    assert sum(item is identifier for item in calls) == 1
    assert sum(item is Choice.FIRST for item in calls) == 1


@pytest.mark.parametrize("mode", ["stdlib", "accelerated"])
def test_dumps__integer_fallback__preserves_later_default_exception(mode, request):
    request.getfixturevalue(mode)
    first, last = object(), object()
    calls = []
    error = ValueError("the caller's later error")

    def counting(value):
        calls.append(value)
        if value is last:
            raise error
        return "first"

    with pytest.raises(ValueError) as raised:
        json_helpers.dumps([first, 2**64, last], default=counting, sort_keys=False)

    assert raised.value is error
    assert calls == [first, last]


@pytest.mark.parametrize("mode", ["stdlib", "accelerated"])
def test_dumps__nested_default_results__preserves_opaque_leaves(mode, request):
    request.getfixturevalue(mode)

    class Leaf:
        def __deepcopy__(self, memo):
            raise AssertionError("encoding must not invoke deepcopy hooks")

    parent, leaf = object(), Leaf()
    calls = []

    def counting(value):
        calls.append(value)
        if value is parent:
            return {"children": [leaf, leaf]}
        assert value is leaf
        return {"serial": sum(item is leaf for item in calls)}

    encoded = json_helpers.dumps([parent, 2**64], default=counting, sort_keys=False)

    assert json.loads(encoded) == [
        {"children": [{"serial": 1}, {"serial": 2}]},
        2**64,
    ]
    assert calls == [parent, leaf, leaf]


@pytest.mark.parametrize("kind", ["dict", "list"])
def test_dumps__default_returns_builtin_subclass__keeps_native_iteration(
    kind, accelerated
):
    class Mapping(dict):
        def items(self):
            raise AssertionError("orjson does not call overridden items")

    class Sequence(list):
        def __iter__(self):
            raise AssertionError("orjson does not call overridden iteration")

    result = Mapping(answer=42) if kind == "dict" else Sequence([42])
    encoded = json_helpers.dumps(
        object(), default=lambda value: result, sort_keys=False
    )

    assert json.loads(encoded) == ({"answer": 42} if kind == "dict" else [42])


@pytest.mark.parametrize("mode", ["stdlib", "accelerated"])
def test_dumps__cyclic_default_result__still_rejects_cycle(mode, request):
    request.getfixturevalue(mode)
    cyclic = []
    cyclic.append(cyclic)

    with pytest.raises(ValueError, match="Circular reference"):
        json_helpers.dumps(object(), default=lambda value: cyclic, sort_keys=False)


@pytest.mark.parametrize("mode", ["stdlib", "accelerated"])
def test_dumps__deep_default_result__does_not_add_recursion_limit(mode, request):
    request.getfixturevalue(mode)
    nested = 0
    for _ in range(600):
        nested = [nested]

    encoded = json_helpers.dumps(
        [object(), 2**64], default=lambda value: nested, sort_keys=False
    )

    assert encoded == b"[" + b"[" * 600 + b"0" + b"]" * 600 + b", 18446744073709551616]"


def test_dumps__default_raises__propagates_the_callers_exception(accelerated):
    """Not orjson's paraphrase of it, which loses the type and the message."""

    class Sentinel(TypeError):
        pass

    class Opaque:
        pass

    def exploding(value):
        raise Sentinel("the caller's own message")

    with pytest.raises(Sentinel, match="the caller's own message"):
        json_helpers.dumps({"v": Opaque()}, default=exploding, sort_keys=False)


def test_dumps__default_raises__is_not_called_twice(accelerated):
    """The retry is for orjson's refusals only; `default` may have side effects."""
    calls = []

    class Opaque:
        pass

    def counting(value):
        calls.append(value)
        raise TypeError("no")

    with pytest.raises(TypeError):
        json_helpers.dumps({"v": Opaque()}, default=counting, sort_keys=False)

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
    accelerated_bytes = json_helpers.dumps({"v": value}, default=None, sort_keys=False)

    monkeypatch.setattr(json_helpers, "_orjson", None)
    stdlib_bytes = json_helpers.dumps({"v": value}, default=None, sort_keys=False)

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
        lambda module: module.dumps({"b": 1, "a": 2}, default=None, sort_keys=True)
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

    assert (
        json.loads(json_helpers.dumps(value, default=flexible, sort_keys=False))
        == expected
    )


@pytest.mark.parametrize("value, expected", SUBCLASSES)
def test_dumps__builtin_subclass__default_is_never_consulted(
    value, expected, accelerated
):
    """Both encoders handle these natively; reaching `default` at all is the bug."""

    def explode(unencodable):
        raise AssertionError(
            f"`default` must not see {type(unencodable).__name__}; it has no case for it"
        )

    assert (
        json.loads(json_helpers.dumps(value, default=explode, sort_keys=False))
        == expected
    )
