import base64
import dataclasses
import decimal
import importlib
import uuid
import warnings
from datetime import date, datetime, timezone
from threading import Lock
from typing import Any, Optional

import numpy as np

import pytest

import opik.jsonable_encoder as jsonable_encoder
from opik.rest_api.core import pydantic_utilities


@dataclasses.dataclass
class Node:
    value: int
    child: Optional["Node"] = None


def test_jsonable_encoder__cyclic_reference():
    """
    Test that the encoder detects cyclic references and does not infinitely recurse.
    """
    # Create a simple two-node cycle: A -> B -> A
    node_a = Node(value=1)
    node_b = Node(value=2)
    node_a.child = node_b
    node_b.child = node_a

    encoded = jsonable_encoder.encode(node_a)
    # The exact format of the cycle marker can vary; we check that:
    # 1. We get some structure for node_a (like a dict).
    # 2. Inside node_a, there's a reference to node_b (a dict).
    # 3. Inside node_b, there's a "cyclic reference" marker instead of a full node_a object.
    print("=" * 150)
    print(encoded)
    assert isinstance(encoded, dict)
    assert "value" in encoded
    assert "child" in encoded

    # node_a.child (which is node_b) should be a dict
    assert isinstance(encoded["child"], dict)
    assert "value" in encoded["child"]
    assert "child" in encoded["child"]

    # node_b.child should be the cycle marker
    cycle_marker = encoded["child"]["child"]
    print("=" * 150)
    print(cycle_marker)
    assert isinstance(cycle_marker, str), (
        "Expected a string marker for cyclic reference"
    )
    assert "<Cyclic reference to " in cycle_marker, (
        "Should contain 'Cyclic reference' text"
    )


def test_jsonable_encoder__repeated_objects_in_list():
    """
    Test that the encoder handles a list of the same object repeated multiple times
    without marking it as a cycle (because it isn't a cycle—just repeated references).
    """
    node = Node(value=42)

    # Put the same node object in a list multiple times
    repeated_list = [node, node, node]

    encoded = jsonable_encoder.encode(repeated_list)
    # We expect a list of three items, each being a dict with `value` = 42, `child` = None
    assert isinstance(encoded, list)
    assert len(encoded) == 3

    for item in encoded:
        assert isinstance(item, dict)
        assert item.get("value") == 42
        assert item.get("child") is None

    # They are distinct dictionary objects, but there is no cycle reference marker
    # because there's no actual cycle. It's just repeated references of the same object.
    assert all("Cyclic reference" not in str(item) for item in encoded)


@pytest.mark.parametrize(
    "obj",
    [
        42,
        42.000,
        "42",
        {
            "p1": 42,
            "p2": 42.000,
            "p3": "42",
        },
        [
            42,
            42.000,
            "42",
        ],
    ],
)
def test_jsonable_encoder__common_types(obj):
    assert obj == jsonable_encoder.encode(obj)


@pytest.mark.parametrize(
    "obj",
    [
        {42, 42.000, "42"},
        (42, 42.000, "42"),
        np.array([42, 42.000, "42"]),
    ],
)
def test_jsonable_encoder__converted_to_list(obj):
    assert list(obj) == jsonable_encoder.encode(obj)


@pytest.mark.parametrize(
    "obj,expected",
    [
        (np.int64(6), 6),
        (np.int32(-7), -7),
        (np.uint8(200), 200),
        (np.float32(2.5), 2.5),
        (np.float16(0.5), 0.5),
        (np.bool_(True), True),
        (np.bool_(False), False),
    ],
)
def test_jsonable_encoder__numpy_scalar__converted_to_python_primitive(obj, expected):
    encoded = jsonable_encoder.encode(obj)

    assert encoded == expected


def test_jsonable_encoder__numpy_scalar_types__are_exact_python_types():
    assert type(jsonable_encoder.encode(np.int64(6))) is int
    assert type(jsonable_encoder.encode(np.float32(2.0))) is float
    assert type(jsonable_encoder.encode(np.bool_(True))) is bool


def test_jsonable_encoder__numpy_longdouble__converted_to_float():
    encoded = jsonable_encoder.encode(np.longdouble(1.5))

    assert encoded == 1.5
    assert type(encoded) is float


@pytest.mark.parametrize("value", ["nan", "inf", "-inf"])
def test_jsonable_encoder__numpy_non_finite_float__same_as_python_float(value):
    encoded_numpy_float32 = jsonable_encoder.encode(np.float32(value))
    encoded_numpy_float64 = jsonable_encoder.encode(np.float64(value))
    encoded_python_float = jsonable_encoder.encode(float(value))

    assert type(encoded_numpy_float32) is float
    assert str(encoded_numpy_float32) == str(encoded_python_float)
    assert str(encoded_numpy_float64) == str(encoded_python_float)


def test_jsonable_encoder__numpy_datetime64__stays_readable_text():
    encoded = jsonable_encoder.encode(np.datetime64("2020-01-01T00:00:00", "ns"))

    assert encoded == "2020-01-01T00:00:00.000000000"


def test_jsonable_encoder__numpy_timedelta64__stays_text_with_unit():
    assert jsonable_encoder.encode(np.timedelta64(5, "s")) == "5 seconds"


def test_jsonable_encoder__numpy_scalars_inside_containers__converted():
    encoded = jsonable_encoder.encode(
        {"total": np.int64(6), "flags": [np.bool_(True)], "mean": np.float32(2.0)}
    )

    assert encoded == {"total": 6, "flags": [True], "mean": 2.0}
    assert type(encoded["total"]) is int
    assert type(encoded["flags"][0]) is bool


@pytest.mark.parametrize(
    "obj,expected",
    [
        (
            date(2020, 1, 1),
            "2020-01-01",
        ),
        (datetime(2020, 1, 1, 10, 20, 30, tzinfo=timezone.utc), "2020-01-01T10:20:30Z"),
    ],
)
def test_jsonable_encoder__datetime_to_text(obj, expected):
    assert expected == jsonable_encoder.encode(obj)


def test_jsonable_encoder__non_serializable_to_text__class():
    class SomeClass:
        a = 1
        b = 42.0

    data = SomeClass()

    assert "SomeClass object at 0x" in jsonable_encoder.encode(data)


def test_jsonable_encoder__non_serializable_to_text__lock():
    data = Lock()

    assert jsonable_encoder.encode(data).startswith(
        "<unlocked _thread.lock object at 0x"
    )


def test_jsonable_encoder__non_serializable_lock_inside_dataclass__lock_converted_to_text():
    @dataclasses.dataclass
    class SomeClass:
        a: Any
        b: Any

    data = SomeClass(a=1, b=Lock())

    encoded = jsonable_encoder.encode(data)
    assert isinstance(encoded, dict)
    assert encoded["a"] == 1
    assert encoded["b"].startswith("<unlocked _thread.lock object at 0x")


@dataclasses.dataclass(slots=True)
class SlotsPoint:
    x: int
    y: int


@dataclasses.dataclass
class DictBase:
    a: int


@dataclasses.dataclass(slots=True)
class SlotsChildOfDictBase(DictBase):
    b: int


@dataclasses.dataclass(slots=True)
class SlotsNode:
    value: int
    child: Optional["SlotsNode"] = None


@dataclasses.dataclass
class InitFalseDefault:
    a: int
    b: int = dataclasses.field(default=7, init=False)


@dataclasses.dataclass(slots=True)
class SlotsInitFalseDefault:
    a: int
    b: int = dataclasses.field(default=7, init=False)


@dataclasses.dataclass(slots=True, frozen=True)
class FrozenSlotsKey:
    x: int


@dataclasses.dataclass(slots=True)
class SlotsWithUnsetField:
    x: int
    y: int = dataclasses.field(init=False)


def test_jsonable_encoder__slots_dataclass__encoded_as_dict():
    assert jsonable_encoder.encode(SlotsPoint(x=1, y=2)) == {"x": 1, "y": 2}


def test_jsonable_encoder__slots_dataclass_nested_in_container__encoded_as_dict():
    encoded = jsonable_encoder.encode({"points": [SlotsPoint(x=1, y=2)]})

    assert encoded == {"points": [{"x": 1, "y": 2}]}


def test_jsonable_encoder__slots_dataclass_inheriting_dict_based_dataclass__all_fields_encoded():
    encoded = jsonable_encoder.encode(SlotsChildOfDictBase(a=1, b=2))

    assert encoded == {"a": 1, "b": 2}


def test_jsonable_encoder__slots_dataclass_cyclic_reference__cycle_detected():
    node_a = SlotsNode(value=1)
    node_b = SlotsNode(value=2, child=node_a)
    node_a.child = node_b

    encoded = jsonable_encoder.encode(node_a)

    assert encoded["value"] == 1
    assert encoded["child"]["value"] == 2
    assert encoded["child"]["child"].startswith("<Cyclic reference to SlotsNode")


def test_jsonable_encoder__same_slots_dataclass_in_sibling_branches__not_reported_as_cycle():
    shared = SlotsPoint(x=1, y=2)

    encoded = jsonable_encoder.encode([shared, shared])

    assert encoded == [{"x": 1, "y": 2}, {"x": 1, "y": 2}]


def test_jsonable_encoder__slots_dataclass_class_object__not_encoded_as_field_dict():
    assert isinstance(jsonable_encoder.encode(SlotsPoint), str)


def test_jsonable_encoder__dataclass_init_false_field_with_default__class_default_not_added():
    assert jsonable_encoder.encode(InitFalseDefault(a=1)) == {"a": 1}


def test_jsonable_encoder__slots_dataclass_unset_field__unset_field_omitted():
    assert jsonable_encoder.encode(SlotsWithUnsetField(x=1)) == {"x": 1}


def test_jsonable_encoder__slots_dataclass_init_false_field_with_default__encoded_like_plain_dataclass():
    assert jsonable_encoder.encode(
        SlotsInitFalseDefault(a=1)
    ) == jsonable_encoder.encode(InitFalseDefault(a=1))


def test_jsonable_encoder__init_false_field_reassigned__encoded_for_plain_and_slots_dataclass():
    plain = InitFalseDefault(a=1)
    plain.b = 9
    slots = SlotsInitFalseDefault(a=1)
    slots.b = 9

    assert jsonable_encoder.encode(plain) == {"a": 1, "b": 9}
    assert jsonable_encoder.encode(slots) == {"a": 1, "b": 9}


def test_jsonable_encoder__slots_dataclass_as_dict_key__key_stringified_and_siblings_kept():
    encoded = jsonable_encoder.encode({FrozenSlotsKey(x=1): "a", "other": "b"})

    assert encoded == {"FrozenSlotsKey(x=1)": "a", "other": "b"}


def test_jsonable_encoder__non_serializable_to_text__bytes():
    data = b"deadbeef"

    assert base64.b64encode(data).decode("utf-8") == jsonable_encoder.encode(data)


def test_jsonable_encoder__uuid_and_decimal_remain_serializable():
    payload = {
        "id": uuid.UUID("12345678-1234-5678-1234-567812345678"),
        "score": decimal.Decimal("1.23"),
    }

    encoded = jsonable_encoder.encode(payload)

    assert encoded["id"] == "12345678-1234-5678-1234-567812345678"
    assert encoded["score"] == "1.23"


def test_pydantic_utilities__import_does_not_emit_v1_py314_warning():
    pytest.importorskip("pydantic")

    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        importlib.reload(pydantic_utilities)

    assert not any(
        "Core Pydantic V1 functionality isn't compatible with Python 3.14 or greater."
        in str(w.message)
        for w in caught
    )


def test_jsonable_encoder__dict_with_tuple_and_frozenset_keys__keys_stringified_and_values_kept():
    encoded = jsonable_encoder.encode(
        {
            (1, 2): {"result": [10, 20]},
            frozenset({3}): "set-value",
            "regular_key": 42,
        }
    )

    assert encoded == {
        "(1, 2)": {"result": [10, 20]},
        "frozenset({3})": "set-value",
        "regular_key": 42,
    }


def test_jsonable_encoder__tuple_key_colliding_with_str_key__both_kept():
    encoded = jsonable_encoder.encode({(1, 2): "t", "(1, 2)": "s"})

    assert encoded == {"(1, 2)": "s", "(1, 2) [tuple]": "t"}


def test_jsonable_encoder__frozenset_key__stringified_in_sorted_order():
    encoded = jsonable_encoder.encode({frozenset({"b", "a", "c"}): 1})

    assert encoded == {"frozenset({'a', 'b', 'c'})": 1}
