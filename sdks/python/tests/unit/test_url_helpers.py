import pytest
from opik import url_helpers


@pytest.mark.parametrize(
    ("base_url", "expected_ping_url"),
    [
        (
            "http://localhost:5173/api/",
            "http://localhost:5173/api/is-alive/ping",
        ),
        (
            "http://localhost:5173/api//",
            "http://localhost:5173/api/is-alive/ping",
        ),
        (
            "https://www.comet.com/opik/api/",
            "https://www.comet.com/opik/api/is-alive/ping",
        ),
    ],
)
def test_get_is_alive_ping_url(base_url: str, expected_ping_url: str) -> None:
    assert url_helpers.get_is_alive_ping_url(base_url) == expected_ping_url
