import datetime
from typing import Optional


def local_timestamp() -> datetime.datetime:
    now = datetime.datetime.now(datetime.timezone.utc)
    return now


def datetime_to_iso8601(value: datetime.datetime) -> str:
    return value.isoformat()


def parse_iso_timestamp(timestamp_str: Optional[str]) -> Optional[datetime.datetime]:
    """Parse an ISO 8601 timestamp string to datetime."""
    if timestamp_str is None:
        return None
    try:
        timestamp_str = timestamp_str.replace("Z", "+00:00")
        return datetime.datetime.fromisoformat(timestamp_str)
    except (ValueError, TypeError):
        return None
