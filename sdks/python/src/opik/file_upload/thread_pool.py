import logging
from concurrent.futures import ThreadPoolExecutor

MAX_POOL_SIZE = 32

LOGGER = logging.getLogger(__name__)


def get_thread_pool(worker_count: int) -> ThreadPoolExecutor:
    pool_size = min(MAX_POOL_SIZE, worker_count)
    LOGGER.debug(
        "Creating file upload thread pool with pool_size: %d, requested workers count: %d ",
        pool_size,
        worker_count,
    )

    return ThreadPoolExecutor(pool_size, thread_name_prefix="opik_file_upload")
