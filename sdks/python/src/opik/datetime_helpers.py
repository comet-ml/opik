import datetime


def local_timestamp() -> datetime.datetime:
    now = datetime.datetime.now(datetime.timezone.utc)
    return now


def datetime_to_iso8601(value: datetime.datetime) -> str:
    return value.isoformat()
