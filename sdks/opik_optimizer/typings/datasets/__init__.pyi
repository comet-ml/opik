from typing import Any


class DownloadConfig:
    def __init__(self, **kwargs: Any) -> None: ...


class _Config:
    HF_CACHE_HOME: str
    HF_DATASETS_CACHE: str


config: _Config


def load_dataset(*args: Any, **kwargs: Any) -> Any: ...
def disable_progress_bar() -> None: ...
def enable_progress_bar() -> None: ...
