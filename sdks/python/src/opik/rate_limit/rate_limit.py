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
    pattern = re.compile(r"opik-(.\w*)-limit")

    for key in rate_limit_response.keys():
        match = pattern.match(key)
        if match:
            rate_limit_name = match.group(1)
            rate_limit = CloudRateLimit(
                bucket_name=rate_limit_response.get(
                    f"opik-{rate_limit_name}-limit", ""
                ),
                rate_limit_name=rate_limit_name,
                remaining_limit=int(
                    rate_limit_response.get(
                        f"opik-{rate_limit_name}-remaining-limit", 0
                    )
                ),
                remaining_limit_reset_time_ms=int(
                    rate_limit_response.get(
                        f"opik-{rate_limit_name}-remaining-limit-ttl-millis", 0
                    )
                ),
            )
            if rate_limit.remaining_limit == 0:
                return rate_limit

    return None
