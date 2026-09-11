"""Unit tests for cast_input_value in in_process_loop."""

from typing import Optional

import pytest

from opik.runner.in_process_loop import cast_input_value
from opik.runner.registry import extract_params


# ---------------------------------------------------------------------------
# None passthrough
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "type_name", ["boolean", "integer", "float", "string", "unknown"]
)
def test_cast_input_value__none_input__returns_none(type_name: str) -> None:
    assert cast_input_value(None, type_name) is None


# ---------------------------------------------------------------------------
# bool
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "value, expected",
    [
        ("true", True),
        ("True", True),
        ("TRUE", True),
        ("false", False),
        ("False", False),
        ("FALSE", False),
        ("0", False),
        ("1", True),
        ("yes", True),
    ],
)
def test_cast_input_value__bool__string_input__returns_bool(
    value: str, expected: bool
) -> None:
    assert cast_input_value(value, "boolean") is expected


@pytest.mark.parametrize("value", [True, False])
def test_cast_input_value__bool__native_bool_input__passthrough(value: bool) -> None:
    assert cast_input_value(value, "boolean") is value


# ---------------------------------------------------------------------------
# int
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "value, expected",
    [
        ("42", 42),
        ("-7", -7),
        ("0", 0),
    ],
)
def test_cast_input_value__int__string_input__returns_int(
    value: str, expected: int
) -> None:
    assert cast_input_value(value, "integer") == expected


@pytest.mark.parametrize("value", ["3.9", "abc"])
def test_cast_input_value__int__non_integer_string__raises_type_error(
    value: str,
) -> None:
    with pytest.raises(TypeError):
        cast_input_value(value, "integer")


@pytest.mark.parametrize("value", [42, -7, 0])
def test_cast_input_value__int__native_int_input__passthrough(value: int) -> None:
    result = cast_input_value(value, "integer")
    assert result == value
    assert type(result) is int


@pytest.mark.parametrize("value", [True, False])
def test_cast_input_value__int__bool_input__raises_type_error(value: bool) -> None:
    with pytest.raises(TypeError):
        cast_input_value(value, "integer")


# ---------------------------------------------------------------------------
# float
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "value, expected",
    [
        ("3.14", 3.14),
        ("-0.5", -0.5),
        ("42", 42.0),
    ],
)
def test_cast_input_value__float__string_input__returns_float(
    value: str, expected: float
) -> None:
    assert cast_input_value(value, "float") == pytest.approx(expected)


@pytest.mark.parametrize("value", [3.14, -0.5, 0.0])
def test_cast_input_value__float__native_float_input__passthrough(
    value: float,
) -> None:
    result = cast_input_value(value, "float")
    assert result == pytest.approx(value)
    assert type(result) is float


def test_cast_input_value__float__int_input__returns_float() -> None:
    result = cast_input_value(5, "float")
    assert result == pytest.approx(5.0)
    assert type(result) is float


# ---------------------------------------------------------------------------
# str
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "value, expected",
    [
        ("hello", "hello"),
        ("", ""),
    ],
)
def test_cast_input_value__str__string_input__passthrough(
    value: str, expected: str
) -> None:
    assert cast_input_value(value, "string") == expected


@pytest.mark.parametrize(
    "value, expected",
    [
        (42, "42"),
        (3.14, "3.14"),
        (True, "True"),
        (False, "False"),
    ],
)
def test_cast_input_value__str__primitive_input__str_coercion(
    value: object, expected: str
) -> None:
    assert cast_input_value(value, "string") == expected


def test_cast_input_value__str__dict_input__json_serialised() -> None:
    assert cast_input_value({"a": 1, "b": "x"}, "string") == '{"a": 1, "b": "x"}'


def test_cast_input_value__str__list_input__json_serialised() -> None:
    assert cast_input_value([1, "two", True], "string") == '[1, "two", true]'


# ---------------------------------------------------------------------------
# unknown type falls back to str behaviour
# ---------------------------------------------------------------------------


def test_cast_input_value__unknown_type__string_input__passthrough() -> None:
    assert cast_input_value("hello", "MyCustomType") == "hello"


def test_cast_input_value__unknown_type__non_string_input__str_coercion() -> None:
    assert cast_input_value(99, "MyCustomType") == "99"


def test_cast_input_value__unknown_type__dict_input__json_serialised() -> None:
    assert cast_input_value({"x": 1}, "MyCustomType") == '{"x": 1}'


# ---------------------------------------------------------------------------
# extract_params: Optional[T] unwrapping (comment #3009010639)
# ---------------------------------------------------------------------------


def test_extract_params__optional_int__unwraps_to_integer() -> None:
    def fn(x: Optional[int]) -> None:
        pass

    params = extract_params(fn)
    assert len(params) == 1
    assert params[0].name == "x"
    assert params[0].type == "integer"


def test_extract_params__optional_bool__unwraps_to_boolean() -> None:
    def fn(flag: Optional[bool]) -> None:
        pass

    params = extract_params(fn)
    assert params[0].type == "boolean"


def test_extract_params__plain_annotation__maps_to_backend_types() -> None:
    def fn(query: str, limit: int, score: float, active: bool) -> None:
        pass

    params = extract_params(fn)
    assert [(p.name, p.type) for p in params] == [
        ("query", "string"),
        ("limit", "integer"),
        ("score", "float"),
        ("active", "boolean"),
    ]


def test_cast_input_value__optional_int_unwrapped__casts_string_to_int() -> None:
    """End-to-end: Optional[int] param stores type='int', cast still works."""

    def fn(x: Optional[int]) -> None:
        pass

    params = extract_params(fn)
    assert params[0].type == "integer"
    assert cast_input_value("42", params[0].type) == 42


# ---------------------------------------------------------------------------
# multi-param combination tests
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "label, inputs, params, expected",
    [
        (
            "all values already typed natively",
            {"text": "hi", "count": 5, "flag": True},
            [("text", "string"), ("count", "integer"), ("flag", "boolean")],
            {"text": "hi", "count": 5, "flag": True},
        ),
        (
            "all values serialised as strings",
            {"text": "hi", "count": "5", "flag": "true"},
            [("text", "string"), ("count", "integer"), ("flag", "boolean")],
            {"text": "hi", "count": 5, "flag": True},
        ),
        (
            "mixed: native int, string bool",
            {"text": "hello", "count": 3, "flag": "false"},
            [("text", "string"), ("count", "integer"), ("flag", "boolean")],
            {"text": "hello", "count": 3, "flag": False},
        ),
        (
            "object input serialised for str param",
            {"text": "hi", "payload": {"key": "val"}, "active": "true"},
            [("text", "string"), ("payload", "string"), ("active", "boolean")],
            {"text": "hi", "payload": '{"key": "val"}', "active": True},
        ),
        (
            "number as string for both int and str params",
            {"label": 99, "score": "7.5"},
            [("label", "string"), ("score", "float")],
            {"label": "99", "score": 7.5},
        ),
        (
            "opik_args key is not in params and passes through unchanged",
            {"query": "hello", "opik_args": {"trace": {"id": "abc"}}},
            [("query", "str")],
            {"query": "hello", "opik_args": {"trace": {"id": "abc"}}},
        ),
    ],
)
def test_cast_input_value__multi_param_combinations(
    label: str,
    inputs: dict,
    params: list,
    expected: dict,
) -> None:
    params_by_name = {name: type_name for name, type_name in params}
    result = {
        key: cast_input_value(value, params_by_name[key])
        if key in params_by_name
        else value
        for key, value in inputs.items()
    }
    assert result == expected
