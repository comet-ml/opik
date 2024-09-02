import datetime
import uuid_extensions

from typing import List, Any

from .. import datetime_helpers
from typing import Optional


def generate_id() -> str:
    return str(uuid_extensions.uuid7())


def datetime_to_iso8601_if_not_None(
    value: Optional[datetime.datetime],
) -> Optional[str]:
    if value is None:
        return None

    return datetime_helpers.datetime_to_iso8601(value)


def list_to_batches(items: List[Any], batch_size: int) -> List[List[Any]]:
    return [items[i : i + batch_size] for i in range(0, len(items), batch_size)]
