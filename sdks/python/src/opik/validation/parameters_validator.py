import logging
import numbers
from collections.abc import Iterable
from typing import Any, List, Optional

import opik.exceptions as exceptions
from . import validator_helpers, parameter, result, validator


class MethodParametersTypeValidator(validator.Validator):
    def __init__(self, method_name: str, class_name: Optional[str] = None):
        if class_name is not None:
            self.prefix = "%s.%s" % (class_name, method_name)
        else:
            self.prefix = method_name
        self.parameters: List[parameter.Parameter] = []
        self.validation_result: Optional[result.ValidationResult] = None

    def add_str_parameter(
        self,
        value: Any,
        name: str,
        possible_values: Optional[List[str]] = None,
        allow_empty: bool = True,
    ) -> None:
        self.parameters.append(
            parameter.create_str_parameter(
                name=name,
                value=value,
                possible_values=possible_values,
                allow_empty=allow_empty,
            )
        )

    def add_bool_parameter(
        self, value: Any, name: str, allow_empty: bool = True
    ) -> None:
        self.parameters.append(
            parameter.create_bool_parameter(
                name=name, value=value, allow_empty=allow_empty
            )
        )

    def add_int_parameter(
        self,
        value: Any,
        name: str,
        possible_values: Optional[List[int]] = None,
        allow_empty: bool = True,
    ) -> None:
        self.parameters.append(
            parameter.create_int_parameter(
                name=name,
                value=value,
                possible_values=possible_values,
                allow_empty=allow_empty,
            )
        )

    def add_float_parameter(
        self,
        value: Any,
        name: str,
        possible_values: Optional[List[float]] = None,
        allow_empty: bool = True,
    ) -> None:
        self.parameters.append(
            parameter.create_float_parameter(
                name=name,
                value=value,
                possible_values=possible_values,
                allow_empty=allow_empty,
            )
        )

    def add_numeric_parameter(
        self,
        value: Any,
        name: str,
        possible_values: Optional[List[numbers.Number]] = None,
        allow_empty: bool = True,
    ) -> None:
        self.parameters.append(
            parameter.create_numeric_parameter(
                name=name,
                value=value,
                possible_values=possible_values,
                allow_empty=allow_empty,
            )
        )

    def add_list_parameter(
        self, value: Any, name: str, allow_empty: bool = True
    ) -> None:
        self.parameters.append(
            parameter.create_list_parameter(
                name=name, value=value, allow_empty=allow_empty
            )
        )

    def add_list_with_strings_parameter(
        self, value: Any, name: str, allow_empty: bool = True
    ) -> None:
        self.parameters.append(
            parameter.create_list_parameter(
                name=name, value=value, allow_empty=allow_empty
            )
        )
        if isinstance(value, Iterable) and not isinstance(value, str):
            for ix, string in enumerate(value):
                name_ = f"{name}[{ix}]"
                self.parameters.append(
                    parameter.create_str_parameter(
                        name=name_, value=string, allow_empty=allow_empty
                    )
                )

    def add_dict_parameter(
        self, value: Any, name: str, allow_empty: bool = True
    ) -> None:
        self.parameters.append(
            parameter.create_dict_parameter(
                name=name, value=value, allow_empty=allow_empty
            )
        )

    def validate(self) -> result.ValidationResult:
        try:
            failures: List[str] = []
            for param in self.parameters:
                valid, msg = validator_helpers.validate_parameter(param)
                if not valid:
                    assert msg is not None, "msg must be set if valid is False"
                    failures.append(msg)

            if len(failures) > 0:
                self.validation_result = result.ValidationResult(
                    failure_reasons=failures, failed=True
                )
            else:
                self.validation_result = result.ValidationResult(failed=False)
        except Exception as e:
            self.validation_result = result.ValidationResult(
                failure_reasons=["Unexpected validation error: %r" % e], failed=True
            )

        return self.validation_result

    def print_result(
        self, logger: logging.Logger, log_level: int = logging.ERROR
    ) -> None:
        if self.validation_result is None:
            logger.log(
                level=log_level, msg="No validation result, please call validate first"
            )
            return

        for msg in self.validation_result.failure_reasons:
            logger.log(log_level, "%s: %s", self.prefix, msg)

    def raise_validation_error(self) -> None:
        if (
            self.validation_result is not None
            and len(self.validation_result.failure_reasons) > 0
        ):
            raise exceptions.ValidationError(
                prefix=self.prefix,
                failure_reasons=self.validation_result.failure_reasons,
            )


def create_validator(
    method_name: str, class_name: Optional[str] = None
) -> MethodParametersTypeValidator:
    return MethodParametersTypeValidator(method_name=method_name, class_name=class_name)
