import pytest
from opik.rate_limit import rate_limit


@pytest.mark.parametrize(
    "rate_limit_dict, expected",
    [
        (
            {
                "opik-customlimit-limit": "bucket",
                "opik-customlimit-remaining-limit": "0",
                "opik-customlimit-remaining-limit-ttl-millis": "997",
            },
            rate_limit.CloudRateLimit(
                bucket_name="bucket",
                rate_limit_name="customlimit",
                remaining_limit=0,
                remaining_limit_reset_time_ms=997,
            ),
        ),
        (
            {
                "opik-available-limit": "bucket",
                "opik-available-remaining-limit": "10",
                "opik-available-remaining-limit-ttl-millis": "997",
            },
            None,
        ),
        ({"foo": "bar"}, None),
        (
            {
                "opik-user-limit": "general_events",
                "opik-user-remaining-limit": "5000",
                "opik-user-remaining-limit-ttl-millis": "56709",
                "opik-workspace-limit": "workspace_events",
                "opik-workspace-remaining-limit": "0",
                "opik-workspace-remaining-limit-ttl-millis": "56700",
            },
            rate_limit.CloudRateLimit(
                bucket_name="workspace_events",
                rate_limit_name="workspace",
                remaining_limit=0,
                remaining_limit_reset_time_ms=56700,
            ),
        ),
    ],
)
def test_parse_rate_limit(rate_limit_dict, expected):
    parsed = rate_limit.parse_rate_limit(rate_limit_dict)
    assert parsed == expected
