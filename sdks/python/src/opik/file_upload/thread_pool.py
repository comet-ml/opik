import logging
import os
from concurrent.futures import ThreadPoolExecutor
from typing import Optional

MAX_POOL_SIZE = 32

LOGGER = logging.getLogger(__name__)


def get_thread_pool(worker_count: Optional[int] = None) -> ThreadPoolExecutor:
    cpu_count = os.cpu_count() or 1

    if worker_count is not None:
        pool_size = worker_count
        LOGGER.debug(
            "Creating file upload thread pool with requested workers count: %d, detected CPU count: %d ",
            worker_count,
            cpu_count,
        )
    else:
        pool_size = min(MAX_POOL_SIZE, cpu_count + 4)
        LOGGER.debug(
            "Creating file upload thread pool with pool_size: %d, detected CPU count: %d ",
            pool_size,
            cpu_count,
        )

    return ThreadPoolExecutor(pool_size, thread_name_prefix="opik_file_upload")
