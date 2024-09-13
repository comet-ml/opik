import copy
import logging
from typing import Any, Dict, Mapping, Optional

from . import logging_messages

LOGGER = logging.getLogger(__name__)


def deepmerge(
    dict1: Dict[str, Any], dict2: Dict[str, Any], max_depth: int = 10
) -> Dict[str, Any]:
    merged = copy.deepcopy(dict1)

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


def remove_none_from_dict(original: Mapping[str, Optional[Any]]) -> Mapping[str, Any]:
    new: Dict[str, Any] = {}

    for key, value in original.items():
        if value is not None:
            new[key] = value

    return new


def _is_dict(item: Any) -> bool:
    return isinstance(item, dict)
