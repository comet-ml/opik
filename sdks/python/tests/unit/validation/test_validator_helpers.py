from typing import Any, List, Optional

from opik.validation import validator_helpers
from opik.validation import parameter

import pytest


@pytest.mark.parametrize(
    "value,allow_empty,expected",
    [
        (None, False, False),
        (None, True, True),
        ("String", False, True),
        ("", False, False),
        (1, False, False),
        ((1, 2, 3), False, False),
        ([2], False, False),
        ({"key": 1.0}, False, False),
    ],
)
def test_validate_type_str(value: Any, allow_empty: bool, expected: bool):
    assert (
        validator_helpers.validate_type_str(value=value, allow_empty=allow_empty)
        == expected
    )


@pytest.mark.parametrize(
    "value,allow_empty,expected",
    [
        (None, False, False),
        (None, True, True),
        (1, False, True),
        ("String", False, False),
        ((1, 2, 3), False, False),
        ([2], False, False),
        ({"key": 1.0}, False, False),
    ],
)
def test_validate_type_int(value: Any, allow_empty: bool, expected: bool):
    assert (
        validator_helpers.validate_type_int(value=value, allow_empty=allow_empty)
        == expected
    )


@pytest.mark.parametrize(
    "value,allow_empty,expected",
    [
        (None, False, False),
        (None, True, True),
        (1.0, False, True),
        (2, False, False),
        ("String", False, False),
        ((1, 2, 3), False, False),
        ({"key": 1.0}, False, False),
    ],
)
def test_validate_type_float(value: Any, allow_empty: bool, expected: bool):
    assert (
        validator_helpers.validate_type_float(value=value, allow_empty=allow_empty)
        == expected
    )


@pytest.mark.parametrize(
    "value,allow_empty,expected",
    [
        (None, False, False),
        (None, True, True),
        (1.0, False, True),
        (2, False, True),
        ("String", False, False),
        ((1, 2, 3), False, False),
        ({"key": 1.0}, False, False),
    ],
)
def test_validate_type_numeric(value: Any, allow_empty: bool, expected: bool):
    assert (
        validator_helpers.validate_type_numeric(value=value, allow_empty=allow_empty)
        == expected
    )


@pytest.mark.parametrize(
    "value,allow_empty,expected",
    [
        (None, False, False),
        (None, True, True),
        (True, False, True),
        (False, False, True),
        ("String", False, False),
        ((1, 2, 3), False, False),
        ([2], False, False),
        ({"key": 1.0}, False, False),
    ],
)
def test_validate_type_bool(value: Any, allow_empty: bool, expected: bool):
    assert (
        validator_helpers.validate_type_bool(value=value, allow_empty=allow_empty)
        == expected
    )


@pytest.mark.parametrize(
    "value,allow_empty,expected",
    [
        (None, False, False),
        (None, True, True),
        ([1.0], False, True),
        ([2], False, True),
        (["String"], False, True),
        ("not_a_list", False, False),
        (10, False, False),
        ((1, 2, 3), False, False),
        ({"key": 1.0}, False, False),
    ],
)
def test_validate_type_list(value: Any, allow_empty: bool, expected: bool):
    assert (
        validator_helpers.validate_type_list(value=value, allow_empty=allow_empty)
        == expected
    )


@pytest.mark.parametrize(
    "value,allow_empty,expected",
    [
        (None, False, False),
        (None, True, True),
        ({"key": 1.0}, False, True),
        ([2], False, False),
        ("not_a_dict", False, False),
        (10, False, False),
        ((1, 2, 3), False, False),
    ],
)
def test_validate_type_dict(value: Any, allow_empty: bool, expected: bool):
    assert (
        validator_helpers.validate_type_dict(value=value, allow_empty=allow_empty)
        == expected
    )


@pytest.mark.parametrize(
    "value,possible_values,allow_empty,expected",
    [
        (None, None, False, False),
        (None, None, True, True),
        ("String", None, False, True),
        (1, None, False, False),
        ((1, 2, 3), None, False, False),
        ([2], None, False, False),
        ({"key": 1.0}, None, False, False),
        ("1", ["1", "2"], False, True),
        ("3", ["1", "2"], False, False),
    ],
)
def test_validate_parameter_type_str(
    value: Any, possible_values: Optional[List], allow_empty: bool, expected: bool
):
    param = parameter.create_str_parameter(
        name="parameter_type_str",
        value=value,
        possible_values=possible_values,
        allow_empty=allow_empty,
    )
    _check_validate_parameter(test_parameter=param, expected=expected)


@pytest.mark.parametrize(
    "value,possible_values,allow_empty,expected",
    [
        (None, None, False, False),
        (None, None, True, True),
        (1, None, False, True),
        ("String", None, False, False),
        ((1, 2, 3), None, False, False),
        ([2], None, False, False),
        (1, [1, 2, 3], False, True),
        (4, [1, 2, 3], False, False),
    ],
)
def test_validate_parameter_type_int(
    value: Any, possible_values: Optional[List], allow_empty: bool, expected: bool
):
    param = parameter.create_int_parameter(
        name="parameter_type_int",
        value=value,
        possible_values=possible_values,
        allow_empty=allow_empty,
    )
    _check_validate_parameter(test_parameter=param, expected=expected)


@pytest.mark.parametrize(
    "value,possible_values,allow_empty,expected",
    [
        (None, None, False, False),
        (None, None, True, True),
        (1.0, None, False, True),
        (2, None, False, False),
        ("String", None, False, False),
        ((1, 2, 3), None, False, False),
        ({"key": 1.0}, None, False, False),
        (1.1, [1.1, 2.1, 3.1], False, True),
        (4.1, [1.1, 2.1, 3.1], False, False),
    ],
)
def test_validate_parameter_type_float(
    value: Any, possible_values: Optional[List], allow_empty: bool, expected: bool
):
    param = parameter.create_float_parameter(
        name="parameter_type_float",
        value=value,
        possible_values=possible_values,
        allow_empty=allow_empty,
    )
    _check_validate_parameter(test_parameter=param, expected=expected)


@pytest.mark.parametrize(
    "value,possible_values,allow_empty,expected",
    [
        (None, None, False, False),
        (None, None, True, True),
        (1.0, None, False, True),
        (2, None, False, True),
        ("String", None, False, False),
        ((1, 2, 3), None, False, False),
        ({"key": 1.0}, None, False, False),
        (1, [1, 2.2, 3], False, True),
        (2.2, [1.1, 2.2, 3], False, True),
        (4, [1, 2.2, 3], False, False),
        (4.2, [1.1, 2.2, 3], False, False),
    ],
)
def test_validate_parameter_type_numeric(
    value: Any, possible_values: Optional[List], allow_empty: bool, expected: bool
):
    param = parameter.create_numeric_parameter(
        name="parameter_type_numeric",
        value=value,
        possible_values=possible_values,
        allow_empty=allow_empty,
    )
    _check_validate_parameter(test_parameter=param, expected=expected)


@pytest.mark.parametrize(
    "value,allow_empty,expected",
    [
        (None, False, False),
        (None, True, True),
        (True, False, True),
        (False, False, True),
        ("String", False, False),
        ((1, 2, 3), False, False),
        ([2], False, False),
        ({"key": 1.0}, False, False),
    ],
)
def test_validate_parameter_type_bool(value: Any, allow_empty: bool, expected: bool):
    param = parameter.create_bool_parameter(
        name="parameter_type_bool", value=value, allow_empty=allow_empty
    )
    _check_validate_parameter(test_parameter=param, expected=expected)


@pytest.mark.parametrize(
    "value,allow_empty,expected",
    [
        (None, False, False),
        (None, True, True),
        ([1.0], False, True),
        ([2], False, True),
        (["String"], False, True),
        ("not_a_list", False, False),
        (10, False, False),
        ((1, 2, 3), False, False),
        ({"key": 1.0}, False, False),
        ([], False, False),
    ],
)
def test_validate_parameter_value_list(value: Any, allow_empty: bool, expected: bool):
    param = parameter.create_list_parameter(
        name="parameter_value_list", value=value, allow_empty=allow_empty
    )
    _check_validate_parameter(test_parameter=param, expected=expected)


@pytest.mark.parametrize(
    "value,allow_empty,expected",
    [
        (None, False, False),
        (None, True, True),
        ({"key": 1.0}, False, True),
        ([2], False, False),
        ("not_a_list", False, False),
        (10, False, False),
        ((1, 2, 3), False, False),
        ([], False, False),
        ({}, False, False),
    ],
)
def test_validate_parameter_value_dict(value: Any, allow_empty: bool, expected: bool):
    param = parameter.create_dict_parameter(
        name="parameter_value_dict", value=value, allow_empty=allow_empty
    )
    _check_validate_parameter(test_parameter=param, expected=expected)


def _check_validate_parameter(test_parameter: parameter.Parameter, expected: bool):
    valid, msg = validator_helpers.validate_parameter(parameter=test_parameter)
    assert valid == expected

    if expected:
        return

    if test_parameter.value is None:
        value_not_empty = False
    elif not isinstance(test_parameter.value, (bool, int, float)):
        value_not_empty = (
            hasattr(test_parameter.value, "__len__") and len(test_parameter.value) > 0
        )
    else:
        value_not_empty = True

    if test_parameter.possible_values is not None:
        possible_values_str = [str(v) for v in test_parameter.possible_values]

        if test_parameter.allow_empty:
            expected_msg = (
                "parameter %r must be one of [%s] or None but %r was given"
                % (
                    test_parameter.name,
                    ", ".join(possible_values_str),
                    test_parameter.value,
                )
            )
        else:
            expected_msg = "parameter %r must be one of [%s] but %r was given" % (
                test_parameter.name,
                ", ".join(possible_values_str),
                test_parameter.value,
            )
    elif value_not_empty:
        param_type = (
            None
            if test_parameter.value is None
            else type(test_parameter.value).__name__
        )

        if test_parameter.allow_empty:
            expected_msg = (
                "parameter %r must be of type(s) %r or None but %r was given"
                % (
                    test_parameter.name,
                    validator_helpers.types_list(test_parameter.types),
                    param_type,
                )
            )
        else:
            expected_msg = "parameter %r must be of type(s) %r but %r was given" % (
                test_parameter.name,
                validator_helpers.types_list(test_parameter.types),
                param_type,
            )
    else:
        expected_msg = "parameter %r must have non empty value but %r was given" % (
            test_parameter.name,
            test_parameter.value,
        )

    assert expected_msg == msg
