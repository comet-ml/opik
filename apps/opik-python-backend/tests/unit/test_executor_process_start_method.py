"""ProcessExecutor workers are forked on Linux regardless of the interpreter default.

Python 3.14 switched the Linux default start method to "forkserver", whose
workers re-import opik before signalling READY and miss the READY timeout on a
loaded host, so the pool never fills and every scoring call returns 503.
"""
import sys
from multiprocessing.context import ForkProcess

import pytest

from opik_backend.executor_process import ProcessExecutor, terminate_worker


@pytest.mark.skipif(sys.platform != "linux", reason="fork is pinned on Linux only")
def test_create_worker_process_forks_and_signals_ready():
    executor = ProcessExecutor()

    executor.create_worker_process()

    assert executor.process_pool.qsize() == 1
    worker = executor.process_pool.get_nowait()
    try:
        assert isinstance(worker["process"], ForkProcess)
        assert worker["process"].is_alive()
    finally:
        terminate_worker(worker)
