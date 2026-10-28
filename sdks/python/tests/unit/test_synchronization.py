from typing import List

import time

from opik import synchronization


def test_wait_for_done__zero_timeout__does_not_wait_for_the_check_to_pass() -> None:
    """A zero timeout is a budget of zero seconds, not an unset timeout.

    Only None means 'wait indefinitely' (that is what the Opik.flush and
    UploadManager.flush docstrings promise). Treating 0 as falsy made
    Opik.flush(timeout=0) -- and any run configured with
    OPIK_DEFAULT_FLUSH_TIMEOUT=0 -- block until the queue drained instead of
    returning immediately.
    """
    calls: List[int] = []

    def check() -> bool:
        # Flips on the fourth call: a correct implementation returns after the
        # first poll, and a broken one still terminates instead of hanging the
        # suite, so the assertion below is what reports the regression.
        calls.append(1)
        return len(calls) > 3

    started = time.monotonic()
    synchronization.wait_for_done(check_function=check, timeout=0, sleep_time=0.05)

    assert len(calls) == 1
    assert time.monotonic() - started < 1.0


def test_wait_for_done__none_timeout__waits_until_the_check_passes() -> None:
    """None keeps meaning no timeout: the call must wait for the check."""
    calls: List[int] = []

    def check() -> bool:
        calls.append(1)
        return len(calls) > 3

    synchronization.wait_for_done(check_function=check, timeout=None, sleep_time=0.01)

    # Four polls from the inner wait loop plus the loop condition of the outer one.
    assert len(calls) >= 4


def test_wait_for_done__positive_timeout__returns_as_soon_as_the_check_passes() -> None:
    calls: List[int] = []

    def check() -> bool:
        calls.append(1)
        return True

    synchronization.wait_for_done(check_function=check, timeout=30, sleep_time=0.01)

    assert len(calls) == 1
