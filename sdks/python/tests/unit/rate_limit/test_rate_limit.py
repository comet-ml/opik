import pytest
from opik.rate_limit import rate_limit


@pytest.mark.parametrize(
    "rate_limit_dict, expected",
    [
        (
            {
                "RateLimit-Reset": "10",
            },
            rate_limit.CloudRateLimit(
                remaining_limit=0,
                remaining_limit_reset_time=10,
            ),
        ),
        (
            {
                "ratelimit-reset": "10",
            },
            rate_limit.CloudRateLimit(
                remaining_limit=0,
                remaining_limit_reset_time=10,
            ),
        ),
        ({"foo": "bar"}, None),
        ({"RateLimit-Reset": "-10"}, None),
    ],
    ids=["standard_name", "lower_case_name", "no_rate_limit", "negative_rate_limit"],
)
def test_parse_rate_limit(rate_limit_dict, expected):
    parsed = rate_limit.parse_rate_limit(rate_limit_dict)
    assert parsed == expected
