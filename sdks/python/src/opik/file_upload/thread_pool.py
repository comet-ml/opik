import logging
import multiprocessing
from concurrent.futures import ThreadPoolExecutor
from typing import Optional

DEFAULT_POOL_RATIO = 4
MAX_POOL_SIZE = 32
DEFAULT_CPU_COUNT = 1

LOGGER = logging.getLogger(__name__)


def get_thread_pool(
    worker_cpu_ratio: int, worker_count: Optional[int] = None
) -> ThreadPoolExecutor:
    try:
        cpu_count = multiprocessing.cpu_count() or 1
    except NotImplementedError:
        cpu_count = DEFAULT_CPU_COUNT

    if worker_count is not None:
        pool_size = worker_count
    else:
        if not isinstance(worker_cpu_ratio, int) or worker_cpu_ratio <= 0:
            LOGGER.debug("Invalid worker_cpu_ratio %r", worker_cpu_ratio)
            worker_cpu_ratio = DEFAULT_POOL_RATIO

        pool_size = min(MAX_POOL_SIZE, cpu_count * worker_cpu_ratio)

    LOGGER.debug(
        "Creating thread pool with pool_size: %d, worker per CPU ratio: %d, detected CPU count: %d ",
        pool_size,
        worker_cpu_ratio,
        cpu_count,
    )

    return ThreadPoolExecutor(pool_size)
