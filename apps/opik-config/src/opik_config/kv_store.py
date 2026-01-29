from datetime import datetime
from threading import Lock
from typing import Any

from .models import ConfigValue


class KVStore:
    def __init__(self) -> None:
        self._data: dict[str, list[ConfigValue]] = {}
        self._lock = Lock()

    def set(self, key: str, value: Any, metadata: dict[str, Any] | None = None) -> ConfigValue:
        with self._lock:
            if key not in self._data:
                self._data[key] = []

            history = self._data[key]
            version = len(history) + 1

            # Preserve fallback from first version if not provided
            final_metadata = metadata or {}
            if history and "fallback" not in final_metadata:
                first_version = history[0]
                if "fallback" in first_version.metadata:
                    final_metadata["fallback"] = first_version.metadata["fallback"]

            config_value = ConfigValue(
                key=key,
                value=value,
                version=version,
                timestamp=datetime.now(),
                metadata=final_metadata,
            )
            history.append(config_value)
            return config_value

    def get_batch(self, keys: list[str], experiment_id: str | None = None) -> dict[str, ConfigValue | None]:
        with self._lock:
            result: dict[str, ConfigValue | None] = {}
            for key in keys:
                value = None

                if experiment_id:
                    exp_key = f"{key}:{experiment_id}"
                    if exp_key in self._data and self._data[exp_key]:
                        value = self._data[exp_key][-1]

                if value is None and key in self._data and self._data[key]:
                    value = self._data[key][-1]

                result[key] = value
            return result

    def list_all(self) -> dict[str, ConfigValue]:
        with self._lock:
            return {key: history[-1] for key, history in self._data.items() if history}
