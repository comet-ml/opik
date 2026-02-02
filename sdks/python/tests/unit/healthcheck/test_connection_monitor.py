from unittest import mock

import pytest

from opik.healthcheck.connection_monitor import ConnectionStatus, OpikConnectionMonitor
from opik.healthcheck.connection_probe import ConnectionProbe, ProbeResult


@pytest.fixture
def mock_probe():
    """Create a mock ConnectionProbe for testing."""
    return mock.MagicMock(spec=ConnectionProbe)


class TestOpikConnectionMonitorReset:
    """Tests for OpikConnectionMonitor.reset method."""

    def test_reset_clears_disconnect_info(self, mock_probe):
        monitor = OpikConnectionMonitor(
            ping_interval=5.0,
            check_timeout=2.0,
            probe=mock_probe,
        )
        monitor.disconnect_time = 12345.0
        monitor.disconnect_reason = "Connection timeout"

        monitor.reset()

        assert monitor.disconnect_time == 0
        assert monitor.disconnect_reason is None


class TestOpikConnectionMonitorConnectionFailed:
    """Tests for OpikConnectionMonitor.connection_failed method."""

    def test_connection_failed_sets_disconnect_info_on_first_failure(self, mock_probe):
        monitor = OpikConnectionMonitor(
            ping_interval=5.0,
            check_timeout=2.0,
            probe=mock_probe,
        )

        with mock.patch("time.time", return_value=12345.0):
            monitor.connection_failed("Network error")

        assert monitor.has_server_connection is False
        assert monitor.disconnect_time == 12345.0
        assert monitor.disconnect_reason == "Network error"

    def test_connection_failed_ignores_subsequent_failures(self, mock_probe):
        monitor = OpikConnectionMonitor(
            ping_interval=5.0,
            check_timeout=2.0,
            probe=mock_probe,
        )

        with mock.patch("time.time", return_value=12345.0):
            monitor.connection_failed("First error")

        # will be ignored because the connection is already marked as failed
        with mock.patch("time.time", return_value=12346.0):
            monitor.connection_failed("Second error")

        # Should preserve the first failure info
        assert monitor.has_server_connection is False
        assert monitor.disconnect_time == 12345.0
        assert monitor.disconnect_reason == "First error"


class TestOpikConnectionMonitorTick:
    """Tests for OpikConnectionMonitor.tick method."""

    def test_tick_performs_check_when_interval_passed(self, mock_probe):
        mock_probe.check_connection.return_value = ProbeResult(
            is_healthy=True, error_message=None
        )
        monitor = OpikConnectionMonitor(
            ping_interval=5.0,
            check_timeout=2.0,
            probe=mock_probe,
        )

        with mock.patch("time.time", return_value=100.0):
            status = monitor.tick()

        mock_probe.check_connection.assert_called_once_with(timeout=2.0)
        assert status == ConnectionStatus.connection_ok
        assert monitor._last_beat == 100.0

    def test_tick_skips_check_when_interval_not_passed(self, mock_probe):
        monitor = OpikConnectionMonitor(
            ping_interval=5.0,
            check_timeout=2.0,
            probe=mock_probe,
        )
        monitor._last_beat = 100.0

        with mock.patch("time.time", return_value=103.0):
            status = monitor.tick()

        mock_probe.check_connection.assert_not_called()
        assert status == ConnectionStatus.connection_ok
        assert monitor._last_beat == 100.0

    def test_tick_returns_connection_failed_when_not_connected_and_interval_not_passed(
        self, mock_probe
    ):
        monitor = OpikConnectionMonitor(
            ping_interval=5.0,
            check_timeout=2.0,
            probe=mock_probe,
        )
        monitor._last_beat = 100.0
        monitor._has_server_connection = False

        with mock.patch("time.time", return_value=103.0):
            status = monitor.tick()

        mock_probe.check_connection.assert_not_called()
        assert status == ConnectionStatus.connection_failed
        assert monitor._last_beat == 100.0

    def test_tick_detects_connection_failure(self, mock_probe):
        mock_probe.check_connection.return_value = ProbeResult(
            is_healthy=False, error_message="Connection timeout"
        )
        monitor = OpikConnectionMonitor(
            ping_interval=5.0,
            check_timeout=2.0,
            probe=mock_probe,
        )

        with mock.patch("time.time", return_value=100.0):
            status = monitor.tick()

        assert status == ConnectionStatus.connection_failed
        assert monitor.has_server_connection is False
        assert monitor.disconnect_reason == "Connection timeout"
        assert monitor._last_beat == 100.0

    def test_tick_detects_connection_restored(self, mock_probe):
        mock_probe.check_connection.return_value = ProbeResult(
            is_healthy=True, error_message=None
        )
        monitor = OpikConnectionMonitor(
            ping_interval=5.0,
            check_timeout=2.0,
            probe=mock_probe,
        )
        monitor._has_server_connection = False

        with mock.patch("time.time", return_value=100.0):
            status = monitor.tick()

        assert status == ConnectionStatus.connection_restored
        assert monitor.has_server_connection is True
        assert monitor._last_beat == 100.0


class TestOpikConnectionMonitorIntegration:
    """Integration tests for OpikConnectionMonitor."""

    def test_full_lifecycle_connect_disconnect_reconnect(self, mock_probe):
        monitor = OpikConnectionMonitor(
            ping_interval=1.0,
            check_timeout=0.5,
            probe=mock_probe,
        )

        # Initial connection is healthy
        mock_probe.check_connection.return_value = ProbeResult(
            is_healthy=True, error_message=None
        )
        with mock.patch("time.time", return_value=0.0):
            status = monitor.tick()
        assert status == ConnectionStatus.connection_ok
        assert monitor.has_server_connection is True

        # Connection fails
        mock_probe.check_connection.return_value = ProbeResult(
            is_healthy=False, error_message="Network error"
        )
        with mock.patch("time.time", return_value=2.0):
            status = monitor.tick()
        assert status == ConnectionStatus.connection_failed
        assert monitor.has_server_connection is False
        assert monitor.disconnect_reason == "Network error"

        # Connection is restored
        mock_probe.check_connection.return_value = ProbeResult(
            is_healthy=True, error_message=None
        )
        with mock.patch("time.time", return_value=4.0):
            status = monitor.tick()
        assert status == ConnectionStatus.connection_restored
        assert monitor.has_server_connection is True

        # Subsequent successful pings return connection_ok
        with mock.patch("time.time", return_value=6.0):
            status = monitor.tick()
        assert status == ConnectionStatus.connection_ok
