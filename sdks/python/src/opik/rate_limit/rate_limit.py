import dataclasses
import re
from typing import Any, Dict, Optional


@dataclasses.dataclass
class CloudRateLimit:
    bucket_name: str
    rate_limit_name: str
    remaining_limit: int
    remaining_limit_reset_time_ms: int

    def retry_after(self) -> float:
        return self.remaining_limit_reset_time_ms / 1000.0


def parse_rate_limit(rate_limit_response: Dict[str, Any]) -> Optional[CloudRateLimit]:
    pattern = re.compile(r"Opik-(.+)-Limit")

    for key in rate_limit_response.keys():
        match = pattern.match(key)
        if match:
            rate_limit_name = match.group(1)
            return CloudRateLimit(
                bucket_name=rate_limit_response.get(
                    f"Opik-{rate_limit_name}-Limit", ""
                ),
                rate_limit_name=rate_limit_name,
                remaining_limit=rate_limit_response.get(
                    f"Opik-{rate_limit_name}-Remaining-Limit", 0
                ),
                remaining_limit_reset_time_ms=rate_limit_response.get(
                    f"Opik-{rate_limit_name}-Remaining-Limit-TTL-Millis", 0
                ),
            )

    return None
