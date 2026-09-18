import threading
from typing import Any, Dict, List

import httpcore
import pytest

from opik.evaluation import asyncio_support

ORIGIN = "https://example.invalid"
TIMEOUT = 10


@pytest.fixture(autouse=True)
def _clean_patch_state():
    """The patch lives on a class and on two module counters, so a test that fails
    inside a context would otherwise leave both behind for the rest of the session.
    The counters only exist once activation is ref-counted, hence ``getattr``."""
    assert getattr(asyncio_support, "_patch_depth", 0) == 0, (
        "a previous test left the counter running"
    )
    original_init = httpcore.AsyncHTTPConnection.__init__
    yield
    httpcore.AsyncHTTPConnection.__init__ = original_init
    if hasattr(asyncio_support, "_patch_depth"):
        asyncio_support._patch_depth = 0
        asyncio_support._original_init = None


def _installed_init() -> object:
    return httpcore.AsyncHTTPConnection.__init__


def _keepalive_expiry_of_new_connection() -> Any:
    return httpcore.AsyncHTTPConnection(origin=ORIGIN)._keepalive_expiry


def _wait_for(event: threading.Event, name: str) -> None:
    if not event.wait(timeout=TIMEOUT):
        raise AssertionError(f"{name} never reached its checkpoint")


def _run(threads: List[threading.Thread]) -> None:
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=3 * TIMEOUT)
        assert not thread.is_alive()


def _first_entrant_leaves_first() -> Dict[str, Any]:
    """A enters, B enters, A leaves while B is still inside.

    Entry order is forced by a checkpoint, not left to the scheduler, because the
    last-leaver-wins assumption only breaks when the *first* entrant leaves first.
    """
    a_entered, b_entered, a_left = (threading.Event() for _ in range(3))
    observations: Dict[str, Any] = {}
    errors: List[BaseException] = []

    def run_a() -> None:
        try:
            with asyncio_support.async_http_connections_expire_immediately():
                a_entered.set()
                _wait_for(b_entered, "the second run")
            observations["after_first_exit"] = _installed_init()
            a_left.set()
        except BaseException as error:  # noqa: BLE001
            errors.append(error)

    def run_b() -> None:
        try:
            _wait_for(a_entered, "the first run")
            with asyncio_support.async_http_connections_expire_immediately():
                b_entered.set()
                _wait_for(a_left, "the first run to leave")
                observations["while_second_still_running"] = _installed_init()
        except BaseException as error:  # noqa: BLE001
            errors.append(error)

    threads = [threading.Thread(target=run_a), threading.Thread(target=run_b)]
    _run(threads)
    assert not errors, errors
    observations["after_both_finished"] = _installed_init()
    return observations


def _last_entrant_leaves_first() -> Dict[str, Any]:
    """The mirrored order: B enters last and leaves first, so the stack unwinds in
    the order the save/restore was written for. Kept as a guard that the ref-count
    does not change what already worked."""
    a_entered, b_entered, b_left = (threading.Event() for _ in range(3))
    observations: Dict[str, Any] = {}
    errors: List[BaseException] = []

    def run_a() -> None:
        try:
            with asyncio_support.async_http_connections_expire_immediately():
                a_entered.set()
                _wait_for(b_entered, "the second run")
                _wait_for(b_left, "the second run to leave")
                observations["while_first_still_running"] = _installed_init()
            observations["after_both_finished"] = _installed_init()
        except BaseException as error:  # noqa: BLE001
            errors.append(error)

    def run_b() -> None:
        try:
            _wait_for(a_entered, "the first run")
            with asyncio_support.async_http_connections_expire_immediately():
                b_entered.set()
            b_left.set()
        except BaseException as error:  # noqa: BLE001
            errors.append(error)

    threads = [threading.Thread(target=run_a), threading.Thread(target=run_b)]
    _run(threads)
    assert not errors, errors
    return observations


def test_the_run_that_leaves_first_does_not_disarm_the_other_run() -> None:
    original_init = _installed_init()

    observations = _first_entrant_leaves_first()

    assert observations["while_second_still_running"] is not original_init


def test_no_patch_is_left_installed_once_every_run_finished() -> None:
    original_init = _installed_init()

    observations = _first_entrant_leaves_first()

    assert observations["after_both_finished"] is original_init


def test_overlapping_runs_that_unwind_in_order_keep_working() -> None:
    original_init = _installed_init()

    observations = _last_entrant_leaves_first()

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
