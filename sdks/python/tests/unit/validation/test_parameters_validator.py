import pytest

from opik.validation import parameters_validator
from opik import exceptions


@pytest.mark.parametrize(
    "value, allow_empty", [("string", False), ("string", True), (None, True)]
)
def test_parameters_type_validator_ok(value, allow_empty):
    class_name = "TestClass"
    method_name = "test_method"
    validator = parameters_validator.create_validator(
        method_name=method_name, class_name=class_name
    )

    validator.add_str_parameter(value, name="string_method", allow_empty=allow_empty)
    result = validator.validate()
    assert result.ok() is True


@pytest.mark.parametrize("value, allow_empty", [(1, False), (1, True), (None, False)])
def test_parameters_type_validator_fail(value, allow_empty):
    class_name = "TestClass"
    method_name = "test_method"
    validator = parameters_validator.create_validator(
        method_name=method_name, class_name=class_name
    )
    validator.add_str_parameter(value, name="string_method", allow_empty=allow_empty)
    result = validator.validate()
    assert result.ok() is False
    assert len(result.failure_reasons) == 1


@pytest.mark.parametrize("value, valid", [(1, True), (1.0, True), ("1", False)])
def test_parameters_type_validator__add_numeric_parameter(value, valid):
    class_name = "TestClass"
    method_name = "test_method"
    validator = parameters_validator.create_validator(
        method_name=method_name, class_name=class_name
    )
    validator.add_numeric_parameter(value, name="numeric_method")
    result = validator.validate()
    assert result.ok() == valid


def test_validate_throw_error():
    with pytest.raises(exceptions.ValidationError):
        class_name = "TestClass"
        method_name = "test_method"
        validator = parameters_validator.create_validator(
            method_name=method_name, class_name=class_name
        )
        validator.add_str_parameter(1, name="string_method")
        validator.validate()
        validator.raise_validation_error()
