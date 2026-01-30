"""HTTP client for the config backend."""

from typing import Any

import requests

from .registration import KeyMetadata


class BackendUnavailableError(Exception):
    pass


class ConfigClient:
    def __init__(self, base_url: str, timeout: float = 5.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._session = requests.Session()

    def resolve(
        self,
        project_id: str,
        env: str,
        keys: list[str],
        mask_id: str | None = None,
        unit_id: str | None = None,
    ) -> dict[str, Any]:
        """
        Resolve config values for a batch of keys.

        Returns dict with:
        - resolved_values: {key: value}
        - resolved_value_ids: {key: int}
        - missing_keys: [key, ...]
        - assigned_variant: str | None
        """
        payload: dict[str, Any] = {
            "project_id": project_id,
            "env": env,
            "keys": keys,
        }
        if mask_id is not None:
            payload["mask_id"] = mask_id
        if unit_id is not None:
            payload["unit_id"] = unit_id

        try:
            response = self._session.post(
                f"{self.base_url}/v1/config/resolve",
                json=payload,
                timeout=self.timeout,
            )
            response.raise_for_status()
        except requests.RequestException as e:
            raise BackendUnavailableError(f"Failed to resolve config: {e}") from e

        return response.json()

    def register_keys(self, project_id: str, keys: list[KeyMetadata], env: str = "prod") -> None:
        """
        Register config key metadata with the backend (best-effort).
        Also publishes default values if not already published.
        """
        payload = {
            "project_id": project_id,
            "env": env,
            "keys": [
                {
                    "key": k.key,
                    "type": k.type_hint,
                    "default_value": k.default_value,
                    "source": {
                        "class_name": k.class_name,
                        "field_name": k.field_name,
                    },
                }
                for k in keys
            ],
        }

        try:
            response = self._session.post(
                f"{self.base_url}/v1/config/keys/register",
                json=payload,
                timeout=self.timeout,
            )
            response.raise_for_status()
        except requests.RequestException:
            pass  # Best-effort, silently fail

    def publish_value(
        self,
        project_id: str,
        env: str,
        key: str,
        value: Any,
        created_by: str | None = None,
    ) -> int | None:
        """Publish a value for a key in an environment."""
        payload: dict[str, Any] = {
            "project_id": project_id,
            "env": env,
            "key": key,
            "value": value,
        }
        if created_by:
            payload["created_by"] = created_by

        try:
            response = self._session.post(
                f"{self.base_url}/v1/config/publish",
                json=payload,
                timeout=self.timeout,
            )
            response.raise_for_status()
            return response.json().get("value_id")
        except requests.RequestException:
            return None

    def create_mask(
        self,
        project_id: str,
        env: str,
        mask_id: str,
        is_ab: bool = False,
        distribution: dict[str, int] | None = None,
        salt: str | None = None,
    ) -> bool:
        """Create or update a mask/experiment."""
        payload: dict[str, Any] = {
            "project_id": project_id,
            "env": env,
            "mask_id": mask_id,
            "is_ab": is_ab,
        }
        if distribution:
            payload["distribution"] = distribution
        if salt:
            payload["salt"] = salt

        try:
            response = self._session.post(
                f"{self.base_url}/v1/config/masks",
                json=payload,
                timeout=self.timeout,
            )
            response.raise_for_status()
            return True
        except requests.RequestException:
            return False

    def set_mask_override(
        self,
        project_id: str,
        env: str,
        mask_id: str,
        variant: str,
        key: str,
        value: Any,
        created_by: str | None = None,
    ) -> int | None:
        """Set an override value for a specific variant of a mask."""
        payload: dict[str, Any] = {
            "project_id": project_id,
            "env": env,
            "mask_id": mask_id,
            "variant": variant,
            "key": key,
            "value": value,
        }
        if created_by:
            payload["created_by"] = created_by

        try:
            response = self._session.post(
                f"{self.base_url}/v1/config/masks/override",
                json=payload,
                timeout=self.timeout,
            )
            response.raise_for_status()
            return response.json().get("value_id")
        except requests.RequestException:
            return None
