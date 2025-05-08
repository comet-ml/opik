import pytest
from opik.rate_limit import rate_limit


@pytest.mark.parametrize(
    "rate_limit_dict, expected",
    [
        (
            {
                "Opik-customlimit-Limit": "bucket",
                "Opik-customlimit-Remaining-Limit": 1,
                "Opik-customlimit-Remaining-Limit-TTL-Millis": 997,
            },
            rate_limit.CloudRateLimit(
                bucket_name="bucket",
                rate_limit_name="customlimit",
                remaining_limit=1,
                remaining_limit_reset_time_ms=997,
            ),
        ),
        ({"foo": "bar"}, None),
    ],
)
def test_parse_rate_limit(rate_limit_dict, expected):
    parsed = rate_limit.parse_rate_limit(rate_limit_dict)
    assert parsed == expected
