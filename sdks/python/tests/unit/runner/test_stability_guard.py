import time
from unittest.mock import patch

from opik.runner.stability_guard import StabilityGuard


class TestStabilityGuard:
    def test_no_crashes__stable(self) -> None:
        guard = StabilityGuard(max_crashes=3, window_seconds=30.0)
        assert guard.is_stable()

    def test_one_crash__stable(self) -> None:
        guard = StabilityGuard(max_crashes=3, window_seconds=30.0)
        guard.record_crash()
        assert guard.is_stable()

    def test_max_crashes_in_window__unstable(self) -> None:
        guard = StabilityGuard(max_crashes=3, window_seconds=30.0)
        for _ in range(3):
            guard.record_crash()
        assert not guard.is_stable()

    def test_crashes_outside_window__stable(self) -> None:
        guard = StabilityGuard(max_crashes=3, window_seconds=10.0)
        base = time.monotonic()
        with patch("opik.runner.stability_guard.time") as mock_time:
            mock_time.monotonic.return_value = base
            for _ in range(2):
                guard.record_crash()

            mock_time.monotonic.return_value = base + 15.0
            guard.record_crash()

            assert guard.is_stable()

    def test_reset__clears_history(self) -> None:
        guard = StabilityGuard(max_crashes=3, window_seconds=30.0)
        for _ in range(3):
            guard.record_crash()
        assert not guard.is_stable()

        guard.reset()
        assert guard.is_stable()

    def test_exactly_at_boundary__still_stable(self) -> None:
        guard = StabilityGuard(max_crashes=3, window_seconds=30.0)
        guard.record_crash()
        guard.record_crash()
        assert guard.is_stable()
