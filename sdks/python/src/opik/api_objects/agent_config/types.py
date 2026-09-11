import typing


class FieldValueSpec(typing.NamedTuple):
    """Describes a single blueprint field's value for write operations.

    Attributes:
        python_type: The Python type of the field (e.g. ``str``, ``int``).
        value: The field value to write.
    """

    python_type: type[typing.Any]
    value: typing.Any
