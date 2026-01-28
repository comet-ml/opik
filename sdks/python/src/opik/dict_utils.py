import copy
import logging
from typing import Any, TypeVar

from . import logging_messages

LOGGER = logging.getLogger(__name__)


def flatten_dict(
    d: dict[str, Any], parent_key: str, delim: str = "."
) -> dict[str, Any]:
    """
    The current implementation does not have max depth restrictions or cyclic references handling!
    """
    items = []  # type: ignore

    for key, value in d.items():
        new_key = f"{parent_key}{delim}{key}" if parent_key else key
        if isinstance(value, dict):
            items.extend(flatten_dict(value, parent_key=new_key, delim=delim).items())
        else:
            items.append((new_key, value))

    return dict(items)


_ValueType = TypeVar("_ValueType")


def keep_only_values_of_type(
    d: dict[str, Any], value_type: type[_ValueType]
) -> dict[str, _ValueType]:
    return {key: value for key, value in d.items() if isinstance(value, value_type)}  # type: ignore


def deepmerge(
    dict1: dict[str, Any], dict2: dict[str, Any], max_depth: int = 10
) -> dict[str, Any]:
    merged = copy.copy(dict1)

    for key, value in dict2.items():
        if (
            key in merged
            and _is_dict(merged[key])
            and _is_dict(value)
            and max_depth > 0
        ):
            merged[key] = deepmerge(merged[key], value, max_depth=max_depth - 1)
        else:
            if key in merged:
                LOGGER.debug(
                    logging_messages.METADATA_KEY_COLLISION_DURING_DEEPMERGE,
                    key,
                    merged[key],
                    value,
                )
            merged[key] = value

    return merged


def remove_none_from_dict(original: dict[str, Any | None]) -> dict[str, Any]:
    new: dict[str, Any] = {}

    for key, value in original.items():
        if value is not None:
            new[key] = value

    return new


def split_dict_by_keys(input_dict: dict, keys: list) -> tuple[dict, dict]:
    subset_dict = {key: input_dict[key] for key in keys if key in input_dict}
    remaining_dict = {
        key: value for key, value in input_dict.items() if key not in subset_dict
    }
    return subset_dict, remaining_dict


def _is_dict(item: Any) -> bool:
    return isinstance(item, dict)
