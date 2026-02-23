import time
import typing

from .client import ConfigData

DEFAULT_TTL_SECONDS = 300


class ConfigCache:
    def __init__(self, ttl_seconds: int = DEFAULT_TTL_SECONDS) -> None:
        self.config_id: typing.Optional[str] = None
        self.blueprint_id: typing.Optional[str] = None
        self.values: typing.Dict[str, typing.Any] = {}
        self._ttl_seconds = ttl_seconds
        self._last_fetch: typing.Optional[float] = None

    def apply(self, config_data: ConfigData) -> None:
        self.config_id = config_data.config_id
        self.blueprint_id = config_data.blueprint_id
        self.values = dict(config_data.values)
        self._last_fetch = time.monotonic()

    def is_stale(self) -> bool:
        if self._last_fetch is None:
            return True
        return (time.monotonic() - self._last_fetch) >= self._ttl_seconds
