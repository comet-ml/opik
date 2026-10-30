import datetime
import email.utils

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
        ({"RateLimit-Reset": "soon"}, None),
        ({"RateLimit-Reset": "1.5"}, None),
    ],
    ids=[
        "standard_name",
        "lower_case_name",
        "no_rate_limit",
        "negative_rate_limit",
        "unparseable",
        "not_an_integer",
    ],
)
def test_parse_rate_limit(rate_limit_dict, expected):
    parsed = rate_limit.parse_rate_limit(rate_limit_dict)
    assert parsed == expected


@pytest.mark.parametrize(
    "headers, expected",
    [
        ({"Retry-After": "5"}, 5.0),
        ({"retry-after": "2.5"}, 2.5),
        ({"Retry-After": "0"}, rate_limit.MIN_RETRY_AFTER_SECONDS),
        ({"Retry-After": "-3"}, rate_limit.MIN_RETRY_AFTER_SECONDS),
        ({"Retry-After": "99999999999"}, rate_limit.MAX_RETRY_AFTER_SECONDS),
        ({}, None),
        ({"Retry-After": "soon"}, None),
        ({"Retry-After": "nan"}, None),
        ({"Retry-After": "inf"}, None),
    ],
    ids=[
        "seconds",
        "lower_case_name",
        "zero_raised_to_minimum",
        "negative_raised_to_minimum",
        "huge_lowered_to_maximum",
        "missing",
        "unparseable",
        "nan",
        "infinite",
    ],
)
def test_parse_retry_after(headers, expected):
    assert rate_limit.parse_retry_after(headers) == expected


def test_parse_retry_after__http_date__seconds_until_that_date():
    retry_at = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(
        seconds=30
    )

    parsed = rate_limit.parse_retry_after(
        {"Retry-After": email.utils.format_datetime(retry_at, usegmt=True)}
    )

    assert 10 < parsed <= 30


def test_parse_retry_after__http_date_with_unknown_zone__read_as_utc():
    retry_at = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(
        seconds=30
    )

    parsed = rate_limit.parse_retry_after(
        {"Retry-After": email.utils.format_datetime(retry_at.replace(tzinfo=None))}
    )

    assert 10 < parsed <= 30


def test_parse_retry_after__http_date_far_in_the_future__lowered_to_maximum():
    retry_at = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=1)

    parsed = rate_limit.parse_retry_after(
        {"Retry-After": email.utils.format_datetime(retry_at, usegmt=True)}
    )

    assert parsed == rate_limit.MAX_RETRY_AFTER_SECONDS
