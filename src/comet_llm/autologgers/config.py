import functools
from typing import Optional

from comet_llm import config, experiment_info


def enabled() -> bool:
    return config.logging_available()


@functools.lru_cache(maxsize=1)
def get_experiment_info() -> Optional[experiment_info.ExperimentInfo]:
    try:
        return experiment_info.get()
    except Exception:
        return None
