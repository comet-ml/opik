"""Key-value store with revision tracking."""

from datetime import datetime
from threading import Lock
from typing import Any
import uuid

from .models import ConfigValue, KeyMetadata as KeyMetadataModel, ResolveResponse


class KVStore:
    def __init__(self) -> None:
        self._data: dict[str, list[ConfigValue]] = {}
        self._key_metadata: dict[str, KeyMetadataModel] = {}
        self._revision: int = 0
        self._lock = Lock()

    def set(self, key: str, value: Any, metadata: dict[str, Any] | None = None) -> ConfigValue:
        with self._lock:
            self._revision += 1

            if key not in self._data:
                self._data[key] = []

            history = self._data[key]
            version = len(history) + 1

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

    def resolve_batch(
        self,
        keys: list[str],
        mask_id: str | None = None,
        unit_id: str | None = None,
    ) -> ResolveResponse:
        """
        Resolve a batch of keys, returning values and metadata.
        Supports mask_id for experiment/variant overrides.
        """
        with self._lock:
            resolved_values: dict[str, Any] = {}
            resolved_value_ids: dict[str, str] = {}

            for key in keys:
                value = None
                value_id = None

                # Check for mask_id-specific override first
                if mask_id:
                    mask_key = f"{key}:{mask_id}"
                    if mask_key in self._data and self._data[mask_key]:
                        cv = self._data[mask_key][-1]
                        value = cv.value
                        value_id = f"{mask_key}:v{cv.version}"

                # Fall back to base key
                if value is None and key in self._data and self._data[key]:
                    cv = self._data[key][-1]
                    value = cv.value
                    value_id = f"{key}:v{cv.version}"

                if value is not None:
                    resolved_values[key] = value
                    resolved_value_ids[key] = value_id or str(uuid.uuid4())

            return ResolveResponse(
                resolved_values=resolved_values,
                resolved_value_ids=resolved_value_ids,
                assigned_variant=mask_id,
                revision=self._revision,
            )

    def register_keys(self, keys: list[dict[str, Any]]) -> None:
        """Register key metadata (best-effort, for discovery/UI)."""
        # First pass: register metadata and collect keys that need defaults
        keys_to_set: list[tuple[str, Any]] = []
        with self._lock:
            for key_data in keys:
                key = key_data["key"]
                self._key_metadata[key] = KeyMetadataModel(
                    key=key,
                    type_hint=key_data.get("type_hint", "Any"),
                    default_value=key_data.get("default_value"),
                    class_name=key_data.get("class_name", ""),
                    field_name=key_data.get("field_name", ""),
                )
                # Collect keys that need defaults set
                if key not in self._data:
                    keys_to_set.append((key, key_data.get("default_value")))

        # Second pass: set defaults outside the lock
        for key, default_value in keys_to_set:
            self.set(key, default_value, metadata={"fallback": default_value})

    def list_all(self) -> dict[str, ConfigValue]:
        with self._lock:
            return {key: history[-1] for key, history in self._data.items() if history}

    def list_key_metadata(self) -> dict[str, KeyMetadataModel]:
        with self._lock:
            return dict(self._key_metadata)

    def get_revision(self) -> int:
        with self._lock:
            return self._revision
