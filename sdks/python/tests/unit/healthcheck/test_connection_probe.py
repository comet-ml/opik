from unittest import mock

import httpx
import pytest

from opik.healthcheck.connection_probe import ConnectionProbe


BASE_URL = "http://example.com"
PING_URL = f"{BASE_URL}/is-alive/ping"


@pytest.fixture
def mock_client():
    """Create a mock HTTP client for testing."""
    return mock.MagicMock()


@pytest.fixture
def mock_response():
    """Create a mock HTTP response."""
    return mock.MagicMock()


@pytest.mark.parametrize(
    "status_code,expected_healthy,expected_error",
    [
        (200, True, None),
        (404, False, "Unexpected status code: 404"),
        (500, False, "Unexpected status code: 500"),
        (503, False, "Unexpected status code: 503"),
    ],
)
def test_check_connection__various_status_codes__returns_expected_health_status(
    mock_client, mock_response, status_code, expected_healthy, expected_error
):
    mock_response.status_code = status_code
    mock_client.get.return_value = mock_response

    with mock.patch(
        "opik.healthcheck.connection_probe.url_helpers"
    ) as mock_url_helpers:
        mock_url_helpers.get_is_alive_ping_url.return_value = PING_URL
        probe = ConnectionProbe(base_url=BASE_URL, client=mock_client)

        result = probe.check_connection(timeout=5.0)

    mock_client.get.assert_called_once_with(PING_URL, timeout=5.0)
    assert result.is_healthy is expected_healthy
    assert result.error_message == expected_error


def test_check_connection__connect_timeout__returns_unhealthy(mock_client):
    mock_client.get.side_effect = httpx.ConnectTimeout("Connection timed out")

    with mock.patch(
        "opik.healthcheck.connection_probe.url_helpers"
    ) as mock_url_helpers:
        mock_url_helpers.get_is_alive_ping_url.return_value = PING_URL
        probe = ConnectionProbe(base_url=BASE_URL, client=mock_client)

        result = probe.check_connection(timeout=5.0)

    assert result.is_healthy is False
    assert "Connection timeout:" in result.error_message


def test_check_connection__unexpected_exception__returns_unhealthy(mock_client):
    mock_client.get.side_effect = RuntimeError("Unexpected network error")

    with mock.patch(
        "opik.healthcheck.connection_probe.url_helpers"
    ) as mock_url_helpers:
        mock_url_helpers.get_is_alive_ping_url.return_value = PING_URL
        probe = ConnectionProbe(base_url=BASE_URL, client=mock_client)

        result = probe.check_connection(timeout=5.0)

    assert result.is_healthy is False
    assert "Unexpected error:" in result.error_message
    assert "Unexpected network error" in result.error_message


def test_check_connection__connection_error__returns_unhealthy(mock_client):
    mock_client.get.side_effect = httpx.ConnectError("Failed to connect")

    with mock.patch(
        "opik.healthcheck.connection_probe.url_helpers"
    ) as mock_url_helpers:
        mock_url_helpers.get_is_alive_ping_url.return_value = PING_URL
        probe = ConnectionProbe(base_url=BASE_URL, client=mock_client)

        result = probe.check_connection(timeout=5.0)

    assert result.is_healthy is False
    assert "Unexpected error:" in result.error_message


def test_check_connection__custom_timeout__uses_provided_timeout(
    mock_client, mock_response
):
    mock_response.status_code = 200
    mock_client.get.return_value = mock_response

    with mock.patch(
        "opik.healthcheck.connection_probe.url_helpers"
    ) as mock_url_helpers:
        mock_url_helpers.get_is_alive_ping_url.return_value = PING_URL
        probe = ConnectionProbe(base_url=BASE_URL, client=mock_client)

        probe.check_connection(timeout=10.0)

    mock_client.get.assert_called_once_with(PING_URL, timeout=10.0)


def test_check_connection__unexpected_exception__logs_error(mock_client, capture_log):
    mock_client.get.side_effect = ValueError("Some unexpected error")

    with mock.patch(
        "opik.healthcheck.connection_probe.url_helpers"
    ) as mock_url_helpers:
        mock_url_helpers.get_is_alive_ping_url.return_value = PING_URL
        probe = ConnectionProbe(base_url=BASE_URL, client=mock_client)

        result = probe.check_connection(timeout=5.0)

    assert result.is_healthy is False
    assert "Unexpected error while checking connection to server" in capture_log.text
