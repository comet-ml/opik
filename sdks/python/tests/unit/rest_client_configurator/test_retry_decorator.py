import datetime
import email.utils

import pytest

from opik.rate_limit import rate_limit
from opik.rest_api.core.api_error import ApiError
from opik.rest_client_configurator import retry_decorator


def _waits_before_giving_up(headers):
    @retry_decorator.opik_rest_retry
    def send():
        raise ApiError(status_code=429, headers=headers, body="Too Many Requests")

    waits = []
    with pytest.raises(ApiError):
        send.retry_with(sleep=waits.append)()
    return waits


@pytest.mark.parametrize(
    "headers, expected_wait",
    [
        ({"Retry-After": "2"}, 2.0),
        ({"retry-after": "2"}, 2.0),
        ({"Retry-After": "0"}, rate_limit.MIN_RETRY_AFTER_SECONDS),
    ],
    ids=["seconds", "lower_case_name", "zero_raised_to_minimum"],
)
def test_opik_rest_retry__429_with_retry_after__waits_as_asked(headers, expected_wait):
    assert _waits_before_giving_up(headers) == [expected_wait, expected_wait]


def test_opik_rest_retry__429_with_retry_after_http_date__waits_until_that_date():
    retry_at = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(
        seconds=30
    )

    waits = _waits_before_giving_up(
        {"Retry-After": email.utils.format_datetime(retry_at, usegmt=True)}
    )

    assert len(waits) == 2
    assert all(10 < wait <= 30 for wait in waits)


@pytest.mark.parametrize(
    "headers",
    [{"Retry-After": "3600"}, {"Retry-After": "soon"}, {}],
    ids=["too_long", "unparseable", "missing"],
)
def test_opik_rest_retry__429_without_usable_short_retry_after__backs_off_exponentially(
    headers,
):
    waits = _waits_before_giving_up(headers)

    assert len(waits) == 2
    assert all(5 <= wait <= 15 for wait in waits)
