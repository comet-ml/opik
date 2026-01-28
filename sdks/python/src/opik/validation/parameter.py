import numbers
from typing import Any


class Parameter:
    __slots__ = ["name", "value", "types", "allow_empty", "possible_values"]

    def __init__(
        self,
        name: str,
        value: Any,
        types: tuple,
        possible_values: list | None = None,
        allow_empty: bool = True,
    ):
        self.name = name
        self.value = value
        self.types = types
        self.allow_empty = allow_empty
        self.possible_values = possible_values


def create_str_parameter(
    name: str,
    value: Any,
    possible_values: list[str] | None = None,
    allow_empty: bool = True,
) -> Parameter:
    return Parameter(
        name=name,
        value=value,
        types=(str,),
        possible_values=possible_values,
        allow_empty=allow_empty,
    )


def create_int_parameter(
    name: str,
    value: Any,
    possible_values: list[int] | None = None,
    allow_empty: bool = True,
) -> Parameter:
    return Parameter(
        name=name,
        value=value,
        types=(int,),
        possible_values=possible_values,
        allow_empty=allow_empty,
    )


def create_float_parameter(
    name: str,
    value: Any,
    possible_values: list[float] | None = None,
    allow_empty: bool = True,
) -> Parameter:
    return Parameter(
        name=name,
        value=value,
        types=(float,),
        possible_values=possible_values,
        allow_empty=allow_empty,
    )


def create_numeric_parameter(
    name: str,
    value: Any,
    possible_values: list[numbers.Number] | None = None,
    allow_empty: bool = True,
) -> Parameter:
    return Parameter(
        name=name,
        value=value,
        types=(float, int),
        possible_values=possible_values,
        allow_empty=allow_empty,
    )


def create_bool_parameter(
    name: str,
    value: Any,
    allow_empty: bool = True,
) -> Parameter:
    return Parameter(
        name=name,
        value=value,
        types=(bool,),
        possible_values=None,
        allow_empty=allow_empty,
    )


def create_list_parameter(
    name: str,
    value: Any,
    allow_empty: bool = True,
) -> Parameter:
    return Parameter(
        name=name,
        value=value,
        types=(list,),
        possible_values=None,
        allow_empty=allow_empty,
    )


def create_dict_parameter(
    name: str,
    value: Any,
    allow_empty: bool = True,
) -> Parameter:
    return Parameter(
        name=name,
        value=value,
        types=(dict,),
        possible_values=None,
        allow_empty=allow_empty,
    )
