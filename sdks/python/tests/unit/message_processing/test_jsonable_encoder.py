from typing import Any
from datetime import date, datetime, timezone
from threading import Lock

import numpy as np
import pytest
import dataclasses

import opik.jsonable_encoder as jsonable_encoder


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
    assert obj == jsonable_encoder.jsonable_encoder(obj)


@pytest.mark.parametrize(
    "obj",
    [
        {42, 42.000, "42"},
        (42, 42.000, "42"),
        np.array([42, 42.000, "42"]),
    ],
)
def test_jsonable_encoder__converted_to_list(obj):
    assert list(obj) == jsonable_encoder.jsonable_encoder(obj)


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
    assert expected == jsonable_encoder.jsonable_encoder(obj)


def test_jsonable_encoder__non_serializable_to_text__class():
    class SomeClass:
        a = 1
        b = 42.0

    data = SomeClass()

    assert "SomeClass object at 0x" in jsonable_encoder.jsonable_encoder(data)


def test_jsonable_encoder__non_serializable_to_text__lock():
    data = Lock()

    assert jsonable_encoder.jsonable_encoder(data).startswith(
        "<unlocked _thread.lock object at 0x"
    )


def test_jsonable_encoder__non_serializable_lock_inside_dataclass__lock_converted_to_text():
    @dataclasses.dataclass
    class SomeClass:
        a: Any
        b: Any

    data = SomeClass(a=1, b=Lock())

    encoded = jsonable_encoder.jsonable_encoder(data)
    assert isinstance(encoded, dict)
    assert encoded["a"] == 1
    assert encoded["b"].startswith("<unlocked _thread.lock object at 0x")


def test_jsonable_encoder__non_serializable_to_text__bytes():
    data = b"deadbeef"

    assert str(data) == jsonable_encoder.jsonable_encoder(data)
