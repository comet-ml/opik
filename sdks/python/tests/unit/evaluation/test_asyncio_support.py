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
    a context would otherwise leave both behind for the rest of the session."""
    assert asyncio_support._patch_depth == 0, "a previous test left the counter running"
    original_init = httpcore.AsyncHTTPConnection.__init__
    yield
    httpcore.AsyncHTTPConnection.__init__ = original_init
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


def test_async_http_connections_expire__runs_leave_out_of_order__patch_stays_installed() -> (
    None
):
    original_init = _installed_init()

    observations = _two_overlapping_runs(first_leaves_first=True)

    assert observations["while_second_still_running"] is not original_init


def test_async_http_connections_expire__all_runs_finish__patch_is_restored() -> None:
    original_init = _installed_init()

    observations = _two_overlapping_runs(first_leaves_first=True)

    assert observations["after_both_finished"] is original_init


def test_async_http_connections_expire__runs_leave_in_order__patch_stays_installed() -> (
    None
):
    original_init = _installed_init()

    observations = _two_overlapping_runs(first_leaves_first=False)

    assert observations["while_first_still_running"] is not original_init
    assert observations["after_both_finished"] is original_init


def test_async_http_connections_expire__inside_and_outside_run__keepalive_is_disabled_only_inside() -> (
    None
):
    assert _keepalive_expiry_of_new_connection() != 0

    with asyncio_support.async_http_connections_expire_immediately():
        assert _keepalive_expiry_of_new_connection() == 0

    assert _keepalive_expiry_of_new_connection() != 0


def test_async_http_connections_expire__sequential_runs__wrappers_do_not_accumulate() -> (
    None
):
    original_init = _installed_init()

    for _ in range(3):
        with asyncio_support.async_http_connections_expire_immediately():
            pass

    assert _installed_init() is original_init


def test_async_http_connections_expire__patch_replaced_mid_run__nested_run_reinstalls_it() -> (
    None
):
    """The depth counter counts our runs only, so a run must not trust it blindly."""
    original_init = _installed_init()

    with asyncio_support.async_http_connections_expire_immediately():
        httpcore.AsyncHTTPConnection.__init__ = original_init  # an outside restore

        with asyncio_support.async_http_connections_expire_immediately():
            assert _keepalive_expiry_of_new_connection() == 0


def test_async_http_connections_expire__external_patch_replaced_mid_run__cleanup_preserves_it() -> (
    None
):
    """The last run out puts back only what it took."""
    original_init = _installed_init()
    external = functools.wraps(original_init)(lambda *a, **k: original_init(*a, **k))  # type: ignore

    with asyncio_support.async_http_connections_expire_immediately():
        httpcore.AsyncHTTPConnection.__init__ = external

    assert _installed_init() is external


def test_async_http_connections_expire__external_patch_before_nested_run__nested_wrapper_preserves_it() -> (
    None
):
    original_init = _installed_init()
    external_calls: List[Any] = []

    def external(*args: Any, **kwargs: Any) -> Any:
        external_calls.append(kwargs.get("keepalive_expiry"))
        return original_init(*args, **kwargs)

    with asyncio_support.async_http_connections_expire_immediately():
        httpcore.AsyncHTTPConnection.__init__ = external  # type: ignore

        with asyncio_support.async_http_connections_expire_immediately():
            assert _keepalive_expiry_of_new_connection() == 0

        assert _installed_init() is not external

    assert _installed_init() is external
    assert external_calls == [0]
