import dataclasses
import datetime
import email.utils
import math
from typing import Any, Dict, Optional

# A `Retry-After: 0` would otherwise turn into a tight resend loop.
MIN_RETRY_AFTER_SECONDS = 1.0


@dataclasses.dataclass
class CloudRateLimit:
    remaining_limit: int
    remaining_limit_reset_time: int

    def retry_after(self) -> float:
        return self.remaining_limit_reset_time


def parse_rate_limit(rate_limit_response: Dict[str, Any]) -> Optional[CloudRateLimit]:
    rate_limit_reset_time = int(rate_limit_response.get("RateLimit-Reset", 0))
    if rate_limit_reset_time == 0:
        rate_limit_reset_time = int(rate_limit_response.get("ratelimit-reset", 0))

    if rate_limit_reset_time > 0:
        return CloudRateLimit(
            remaining_limit=0,
            remaining_limit_reset_time=rate_limit_reset_time,
        )

    return None


def parse_retry_after(headers: Dict[str, Any]) -> Optional[float]:
    """Seconds to wait according to a standard ``Retry-After`` header, given either
    as delta-seconds or as an HTTP date. Returns None if the header is absent or
    unparseable."""
    value = headers.get("Retry-After", headers.get("retry-after"))
    if value is None:
        return None

    try:
        seconds = float(value)
    except (TypeError, ValueError):
        try:
            retry_at = email.utils.parsedate_to_datetime(value)
        except (TypeError, ValueError):
            return None
        # A "-0000" zone parses as naive; RFC 5322 still means UTC by it.
        if retry_at.tzinfo is None:
            retry_at = retry_at.replace(tzinfo=datetime.timezone.utc)
        seconds = (
            retry_at - datetime.datetime.now(datetime.timezone.utc)
        ).total_seconds()

    if not math.isfinite(seconds):
        return None

    return max(seconds, MIN_RETRY_AFTER_SECONDS)
