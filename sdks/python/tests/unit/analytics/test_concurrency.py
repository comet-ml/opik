"""
Reporting is once per process. A check-then-add across threads is not once per
process: every thread that raced to the first call gets through, and a threaded
application reports one copy per thread.
"""

import concurrent.futures as futures
import threading
import time

import pytest

from opik import analytics
from opik.analytics import api


@pytest.fixture(autouse=True)
def widen_the_window(monkeypatch):
    """
    Forces the interleaving instead of hoping for it. In a real run the gap between
    noticing an event is new and claiming it holds a stack walk, which is wide enough
    for every waiting thread to get through; in a unit test it is a few bytecodes and
    the race almost never shows. Yielding here reproduces the real timing.
    """
    original = api._reported_from_inside_the_sdk

    def slow_check():
        time.sleep(0.01)
        return original()

    monkeypatch.setattr(api, "_reported_from_inside_the_sdk", slow_check)


def test_track_event__same_event_from_many_threads__reported_once(recording_worker):
    start = threading.Barrier(16)

    def report(_):
        # Line every thread up first, so they contend on the very first call rather
        # than arriving one after another and each seeing the event already reported.
        start.wait(timeout=10)
        analytics.track_event("client", "create_dataset")

    with futures.ThreadPoolExecutor(max_workers=16) as pool:
        list(pool.map(report, range(16)))

    assert recording_worker.names == ["opik_python_sdk__client__create_dataset"]


def test_track_event__different_events_from_many_threads__each_reported_once(
    recording_worker,
):
    actions = [f"action_{index}" for index in range(8)]
    # Each action reported by two threads at once. The barrier has to match the number
    # of threads that can actually be running, or it waits for arrivals that the pool
    # is not free to make.
    tasks = actions * 2
    start = threading.Barrier(len(tasks))

    def report(action):
        start.wait(timeout=10)
        analytics.track_event("client", action)

    with futures.ThreadPoolExecutor(max_workers=len(tasks)) as pool:
        list(pool.map(report, tasks))

    assert sorted(recording_worker.names) == sorted(
        f"opik_python_sdk__client__{action}" for action in actions
    )
