import functools
import threading
from typing import Any, Dict, List

import httpcore
import pytest

from opik.evaluation import asyncio_support

ORIGIN = "https://example.invalid"
TIMEOUT = 10


@pytest.fixture(autouse=True)
def _clean_patch_state():
    """The patch lives on a class and on module counters, so a test that fails inside
    a context would otherwise leave both behind for the rest of the session. The
    counters only exist once activation is ref-counted, hence ``getattr``."""
    assert getattr(asyncio_support, "_patch_depth", 0) == 0, (
        "a previous test left the counter running"
    )
    original_init = httpcore.AsyncHTTPConnection.__init__
    yield
    httpcore.AsyncHTTPConnection.__init__ = original_init
    if hasattr(asyncio_support, "_patch_depth"):
        asyncio_support._patch_depth = 0
        asyncio_support._original_init = None
        asyncio_support._installed_init = None


def _installed_init() -> object:
    return httpcore.AsyncHTTPConnection.__init__


def _keepalive_expiry_of_new_connection() -> Any:
    return httpcore.AsyncHTTPConnection(origin=ORIGIN)._keepalive_expiry


def _start_and_join(threads: List[threading.Thread]) -> None:
    """Start every worker, then join every worker even when one already failed, so
    no thread can outlive the test and mutate the shared state after teardown."""
    for thread in threads:
        thread.start()
    stuck = []
    for thread in threads:
        thread.join(timeout=3 * TIMEOUT)
        if thread.is_alive():
            stuck.append(thread.name)
    assert not stuck, f"worker threads did not stop: {stuck}"


def _wait_for(event: threading.Event, name: str, errors: List[BaseException]) -> bool:
    if not event.wait(timeout=TIMEOUT):
        errors.append(AssertionError(f"{name} never reached its checkpoint"))
        return False
    return True


def _two_overlapping_runs(first_leaves_first: bool) -> Dict[str, Any]:
    """Two runs whose contexts overlap, with the entry order forced by checkpoints.

    ``first_leaves_first`` selects whether the first entrant also leaves first, which
    is the order the save/restore cannot handle, or last, which is the order it can.
    """
    a_entered = threading.Event()
    b_entered = threading.Event()
    a_left = threading.Event()
    b_left = threading.Event()
    observations: Dict[str, Any] = {}
    errors: List[BaseException] = []

    def run_a() -> None:
        try:
            with asyncio_support.async_http_connections_expire_immediately():
                a_entered.set()
                if not _wait_for(b_entered, "the second run", errors):
                    pass
                elif first_leaves_first:
                    pass
                elif _wait_for(b_left, "the second run to leave", errors):
                    observations["while_first_still_running"] = _installed_init()
            if first_leaves_first:
                a_left.set()
            else:
                observations["after_both_finished"] = _installed_init()
        except BaseException as error:  # noqa: BLE001
            errors.append(error)

    def run_b() -> None:
        try:
            if not _wait_for(a_entered, "the first run", errors):
                return
            with asyncio_support.async_http_connections_expire_immediately():
                b_entered.set()
                if first_leaves_first:
                    if _wait_for(a_left, "the first run to leave", errors):
                        observations["while_second_still_running"] = _installed_init()
                else:
                    b_left.set()
            if not first_leaves_first:
                observations["after_both_finished"] = _installed_init()
        except BaseException as error:  # noqa: BLE001
            errors.append(error)

    _start_and_join(
        [
            threading.Thread(target=run_a, name="run-a"),
            threading.Thread(target=run_b, name="run-b"),
        ]
    )
    assert not errors, errors
    if first_leaves_first:
        observations["after_both_finished"] = _installed_init()
    return observations


def test_the_run_that_leaves_first_does_not_disarm_the_other_run() -> None:
    original_init = _installed_init()

    observations = _two_overlapping_runs(first_leaves_first=True)

    assert observations["while_second_still_running"] is not original_init


def test_no_patch_is_left_installed_once_every_run_finished() -> None:
    original_init = _installed_init()

    observations = _two_overlapping_runs(first_leaves_first=True)

    assert observations["after_both_finished"] is original_init


def test_overlapping_runs_that_unwind_in_order_keep_working() -> None:
    original_init = _installed_init()

    observations = _two_overlapping_runs(first_leaves_first=False)

    assert observations["while_first_still_running"] is not original_init
    assert observations["after_both_finished"] is original_init


def test_connections_expire_immediately_inside_a_run_and_not_outside() -> None:
    assert _keepalive_expiry_of_new_connection() != 0

    with asyncio_support.async_http_connections_expire_immediately():
        assert _keepalive_expiry_of_new_connection() == 0

    assert _keepalive_expiry_of_new_connection() != 0


def test_sequential_runs_do_not_accumulate_wrappers() -> None:
    original_init = _installed_init()

    for _ in range(3):
        with asyncio_support.async_http_connections_expire_immediately():
            pass

    assert _installed_init() is original_init


def test_the_patch_is_reinstalled_if_something_restores_it_mid_run() -> None:
    """The depth counter counts our runs only, so a run must not trust it blindly."""
    original_init = _installed_init()

    with asyncio_support.async_http_connections_expire_immediately():
        httpcore.AsyncHTTPConnection.__init__ = original_init  # an outside restore

        with asyncio_support.async_http_connections_expire_immediately():
            assert _keepalive_expiry_of_new_connection() == 0


def test_cleanup_does_not_discard_a_patch_installed_during_our_runs() -> None:
    """The last run out puts back only what it took."""
    original_init = _installed_init()
    external = functools.wraps(original_init)(lambda *a, **k: original_init(*a, **k))  # type: ignore

    with asyncio_support.async_http_connections_expire_immediately():
        httpcore.AsyncHTTPConnection.__init__ = external

    assert _installed_init() is external
