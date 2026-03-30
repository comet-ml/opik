"""Unit tests for cast_input_value in in_process_loop."""

import pytest

from opik.runner.in_process_loop import cast_input_value


# --- null passthrough ---


@pytest.mark.parametrize("type_name", ["bool", "int", "float", "str", "unknown"])
def test_none_passthrough(type_name: str) -> None:
    assert cast_input_value(None, type_name) is None


# --- bool ---


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
        ("1", False),
    ],
)
def test_bool_from_string(value: str, expected: bool) -> None:
    assert cast_input_value(value, "bool") is expected


@pytest.mark.parametrize("value", [True, False])
def test_bool_native_passthrough(value: bool) -> None:
    result = cast_input_value(value, "bool")
    assert result is value


# --- int ---


@pytest.mark.parametrize(
    "value, expected",
    [
        ("42", 42),
        ("-7", -7),
        ("0", 0),
    ],
)
def test_int_from_string(value: str, expected: int) -> None:
    assert cast_input_value(value, "int") == expected


@pytest.mark.parametrize("value", [42, -7, 0])
def test_int_native_passthrough(value: int) -> None:
    result = cast_input_value(value, "int")
    assert result == value
    assert type(result) is int


def test_int_does_not_treat_bool_as_int() -> None:
    # True is an int subclass in Python; we want it re-cast to 1 via int()
    assert cast_input_value(True, "int") == 1
    assert cast_input_value(False, "int") == 0


# --- float ---


@pytest.mark.parametrize(
    "value, expected",
    [
        ("3.14", 3.14),
        ("-0.5", -0.5),
        ("42", 42.0),
    ],
)
def test_float_from_string(value: str, expected: float) -> None:
    assert cast_input_value(value, "float") == pytest.approx(expected)


@pytest.mark.parametrize("value", [3.14, -0.5, 0.0])
def test_float_native_passthrough(value: float) -> None:
    result = cast_input_value(value, "float")
    assert result == pytest.approx(value)
    assert type(result) is float


def test_float_from_int() -> None:
    result = cast_input_value(5, "float")
    assert result == pytest.approx(5.0)
    assert type(result) is float


# --- str ---


@pytest.mark.parametrize(
    "value, expected",
    [
        ("hello", "hello"),
        ("", ""),
    ],
)
def test_str_passthrough(value: str, expected: str) -> None:
    assert cast_input_value(value, "str") == expected


@pytest.mark.parametrize(
    "value, expected",
    [
        (42, "42"),
        (3.14, "3.14"),
        (True, "True"),
        (False, "False"),
    ],
)
def test_str_from_primitive(value: object, expected: str) -> None:
    assert cast_input_value(value, "str") == expected


def test_str_dict_json_serialised() -> None:
    assert cast_input_value({"a": 1, "b": "x"}, "str") == '{"a": 1, "b": "x"}'


def test_str_list_json_serialised() -> None:
    assert cast_input_value([1, "two", True], "str") == '[1, "two", true]'


# --- unknown type falls back to str ---


def test_unknown_type_string_passthrough() -> None:
    assert cast_input_value("hello", "MyCustomType") == "hello"


def test_unknown_type_non_string_coerced() -> None:
    assert cast_input_value(99, "MyCustomType") == "99"


def test_unknown_type_dict_json_serialised() -> None:
    assert cast_input_value({"x": 1}, "MyCustomType") == '{"x": 1}'


# --- multi-param combination tests ---


@pytest.mark.parametrize(
    "label, inputs, params, expected",
    [
        (
            "all values already typed natively",
            {"text": "hi", "count": 5, "flag": True},
            [("text", "str"), ("count", "int"), ("flag", "bool")],
            {"text": "hi", "count": 5, "flag": True},
        ),
        (
            "all values serialised as strings",
            {"text": "hi", "count": "5", "flag": "true"},
            [("text", "str"), ("count", "int"), ("flag", "bool")],
            {"text": "hi", "count": 5, "flag": True},
        ),
        (
            "mixed: native int, string bool",
            {"text": "hello", "count": 3, "flag": "false"},
            [("text", "str"), ("count", "int"), ("flag", "bool")],
            {"text": "hello", "count": 3, "flag": False},
        ),
        (
            "object input serialised for str param",
            {"text": "hi", "payload": {"key": "val"}, "active": "true"},
            [("text", "str"), ("payload", "str"), ("active", "bool")],
            {"text": "hi", "payload": '{"key": "val"}', "active": True},
        ),
        (
            "number as string for both int and str params",
            {"label": 99, "score": "7.5"},
            [("label", "str"), ("score", "float")],
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
def test_combination(
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
