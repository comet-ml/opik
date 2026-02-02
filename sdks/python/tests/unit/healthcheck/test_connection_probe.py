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


def test_check_connection_returns_healthy_on_200(mock_client, mock_response):
    mock_response.status_code = 200
    mock_client.get.return_value = mock_response

    with mock.patch(
        "opik.healthcheck.connection_probe.url_helpers"
    ) as mock_url_helpers:
        mock_url_helpers.get_is_alive_ping_url.return_value = PING_URL
        probe = ConnectionProbe(base_url=BASE_URL, client=mock_client)

        result = probe.check_connection(timeout=5.0)

    mock_client.get.assert_called_once_with(PING_URL, timeout=5.0)
    assert result.is_healthy is True
    assert result.error_message is None


def test_check_connection_returns_unhealthy_on_non_200(mock_client, mock_response):
    mock_response.status_code = 500
    mock_client.get.return_value = mock_response

    with mock.patch(
        "opik.healthcheck.connection_probe.url_helpers"
    ) as mock_url_helpers:
        mock_url_helpers.get_is_alive_ping_url.return_value = PING_URL
        probe = ConnectionProbe(base_url=BASE_URL, client=mock_client)

        result = probe.check_connection(timeout=5.0)

    assert result.is_healthy is False
    assert result.error_message == "Unexpected status code: 500"


def test_check_connection_returns_unhealthy_on_404(mock_client, mock_response):
    mock_response.status_code = 404
    mock_client.get.return_value = mock_response

    with mock.patch(
        "opik.healthcheck.connection_probe.url_helpers"
    ) as mock_url_helpers:
        mock_url_helpers.get_is_alive_ping_url.return_value = PING_URL
        probe = ConnectionProbe(base_url=BASE_URL, client=mock_client)

        result = probe.check_connection(timeout=5.0)

    assert result.is_healthy is False
    assert result.error_message == "Unexpected status code: 404"


def test_check_connection_returns_unhealthy_on_503(mock_client, mock_response):
    mock_response.status_code = 503
    mock_client.get.return_value = mock_response

    with mock.patch(
        "opik.healthcheck.connection_probe.url_helpers"
    ) as mock_url_helpers:
        mock_url_helpers.get_is_alive_ping_url.return_value = PING_URL
        probe = ConnectionProbe(base_url=BASE_URL, client=mock_client)

        result = probe.check_connection(timeout=5.0)

    assert result.is_healthy is False
    assert result.error_message == "Unexpected status code: 503"


def test_check_connection_handles_connect_timeout(mock_client):
    mock_client.get.side_effect = httpx.ConnectTimeout("Connection timed out")

    with mock.patch(
        "opik.healthcheck.connection_probe.url_helpers"
    ) as mock_url_helpers:
        mock_url_helpers.get_is_alive_ping_url.return_value = PING_URL
        probe = ConnectionProbe(base_url=BASE_URL, client=mock_client)

        result = probe.check_connection(timeout=5.0)

    assert result.is_healthy is False
    assert "Connection timeout:" in result.error_message


def test_check_connection_handles_unexpected_exception(mock_client):
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


def test_check_connection_handles_connection_error(mock_client):
    mock_client.get.side_effect = httpx.ConnectError("Failed to connect")

    with mock.patch(
        "opik.healthcheck.connection_probe.url_helpers"
    ) as mock_url_helpers:
        mock_url_helpers.get_is_alive_ping_url.return_value = PING_URL
        probe = ConnectionProbe(base_url=BASE_URL, client=mock_client)

        result = probe.check_connection(timeout=5.0)

    assert result.is_healthy is False
    assert "Unexpected error:" in result.error_message


def test_check_connection_uses_provided_timeout(mock_client, mock_response):
    mock_response.status_code = 200
    mock_client.get.return_value = mock_response

    with mock.patch(
        "opik.healthcheck.connection_probe.url_helpers"
    ) as mock_url_helpers:
        mock_url_helpers.get_is_alive_ping_url.return_value = PING_URL
        probe = ConnectionProbe(base_url=BASE_URL, client=mock_client)

        probe.check_connection(timeout=10.0)

    mock_client.get.assert_called_once_with(PING_URL, timeout=10.0)


def test_check_connection_logs_unexpected_exception(mock_client):
    mock_client.get.side_effect = ValueError("Some unexpected error")

    with mock.patch(
        "opik.healthcheck.connection_probe.url_helpers"
    ) as mock_url_helpers:
        mock_url_helpers.get_is_alive_ping_url.return_value = PING_URL
        probe = ConnectionProbe(base_url=BASE_URL, client=mock_client)

        with mock.patch("opik.healthcheck.connection_probe.LOGGER") as mock_logger:
            result = probe.check_connection(timeout=5.0)

    assert result.is_healthy is False
    mock_logger.exception.assert_called_once()
    call_args = mock_logger.exception.call_args
    assert "Unexpected error while checking connection to server" in call_args[0][0]
