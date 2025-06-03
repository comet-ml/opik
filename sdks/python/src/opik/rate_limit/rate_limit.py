import dataclasses
from typing import Any, Dict, Optional


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
