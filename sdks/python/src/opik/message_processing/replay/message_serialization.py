import datetime
import json
import re
from typing import Any, Dict, Set, Type

from opik import jsonable_encoder
from .. import messages


# ISO 8601 datetime pattern (matches formats like 2024-01-15T10:30:00, 2024-01-15T10:30:00.123456, with optional timezone)
_ISO_DATETIME_PATTERN = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})?$"
)

# Field names that are known to contain datetime values in message classes.
# Only these fields will be converted from ISO strings back to datetime objects
# during deserialization, to avoid false conversions of ISO-like strings in
# arbitrary dictionary fields (e.g., input, output, metadata).
DATETIME_FIELD_NAMES: Set[str] = {"start_time", "end_time", "last_updated_at"}


def datetime_object_hook(obj: Dict[str, Any]) -> Dict[str, Any]:
    """Object hook for json.loads that converts ISO format strings to datetime objects.

    Only converts values for keys listed in DATETIME_FIELD_NAMES to avoid
    false conversions of ISO-like strings in arbitrary data fields.
    """
    for key in DATETIME_FIELD_NAMES:
        value = obj.get(key)
        if isinstance(value, str) and _ISO_DATETIME_PATTERN.match(value):
            try:
                obj[key] = datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError:
                pass  # Not a valid datetime, keep as string
    return obj


def serialize_message(message: messages.BaseMessage) -> str:
    """
    Serializes a message object into a JSON string.

    This function converts a given message object, which must be a subclass
    of `messages.BaseMessage`, into a dictionary format suitable for database
    storage. The dictionary is first encoded via `jsonable_encoder.encode`
    (which converts datetime objects to ISO 8601 strings including timezone
    info via `datetime_utils.serialize_datetime`) and then serialized to a
    JSON string using the standard `json.dumps`.

    Args:
        message: The message object to be serialized.

    Returns:
        str: A JSON string representation of the message object.
    """
    data = message.as_db_message_dict()
    encoded_data_dict = jsonable_encoder.encode(data)
    return json.dumps(encoded_data_dict)


def deserialize_message(message_class: Type[messages.T], json_str: str) -> messages.T:
    """
    Deserializes a JSON string into an instance of a specified message class.

    This function takes a JSON string and transforms it into an instance of the
    provided message class. The conversion process includes mapping JSON structures
    into their equivalent class representations, with custom handling of date and
    time fields.

    Args:
        message_class: The class type into which the JSON string
            will be deserialized. This must be a subclass of `messages.T`.
        json_str: The JSON string to be deserialized.

    Returns:
        An instance of the provided message class populated with data
        from the JSON string.
    """
    data = json.loads(json_str, object_hook=datetime_object_hook)
    return messages.from_db_message_dict(data=data, message_class=message_class)
