import base64
import dataclasses
from datetime import date, datetime, timezone
from threading import Lock
from typing import Any, Optional

import numpy as np
import pytest

import opik.jsonable_encoder as jsonable_encoder


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
    assert isinstance(
        cycle_marker, str
    ), "Expected a string marker for cyclic reference"
    assert (
        "<Cyclic reference to " in cycle_marker
    ), "Should contain 'Cyclic reference' text"


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


def test_jsonable_encoder__non_serializable_to_text__bytes():
    data = b"deadbeef"

    assert base64.b64encode(data).decode("utf-8") == jsonable_encoder.encode(data)
