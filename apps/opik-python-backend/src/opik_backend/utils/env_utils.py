import os


def is_rq_worker_enabled() -> bool:
    return os.getenv('RQ_WORKER_ENABLED', 'false').lower() == 'true'


