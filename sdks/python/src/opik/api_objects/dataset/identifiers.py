"""The one form of a dataset item's identifiers, and the check that they are storable.

`id`, `trace_id` and `span_id` are UUID columns server-side, and `DatasetItem` declares
them `pydantic.SkipValidation[str]` -- so whatever a caller passes arrives here
unconverted, a `uuid.UUID` object most usefully. Everything that has to agree on what an
item *is* comes through this module: the request body, and the caches keyed by id.
"""

import uuid
from typing import Any, Optional


def canonical_id(value: Any) -> str:
    """The string form of an identifier that an item definitely has.

    A `uuid.UUID` and its string form must not become two identities for one item: the
    wire would stringify the object on its own, but a cache keyed by the object would
    then miss every lookup made with the string.
    """
    return value if isinstance(value, str) else str(value)


def optional_canonical_id(value: Any) -> Optional[str]:
    """`canonical_id` for the identifiers an item may simply not have.

    Separate from `canonical_id` rather than one function returning `Optional[str]`,
    because the callers disagree about what `None` means -- an absent `trace_id` is sent
    as null, while a `None` passed to `delete` identifies nothing and is an error. Asking
    for the optional form is how a caller says which it expects.
    """
    return None if value is None else canonical_id(value)


def validate_identifier(value: Any, field: str, index: Optional[int] = None) -> None:
    """Reject an identifier the backend cannot store, before the request goes out.

    Anything that is not a UUID is refused server-side with a deserialisation error that
    names neither the item nor the field. A prepared body never passes through the
    generated model, so this is the only check between the caller and that.

    `index` is the item's position when the caller has one; a generator does not.
    """
    canonical = optional_canonical_id(value)
    if canonical is None:
        return
    try:
        uuid.UUID(canonical)
    except ValueError:
        where = "" if index is None else f" at index {index}"
        raise ValueError(
            f"Dataset item{where} has an invalid {field}: {value!r} is not a UUID"
        ) from None
