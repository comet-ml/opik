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
            payload["mask_id"] = str(mask_id) if not isinstance(mask_id, str) else mask_id
        if unit_id is not None:
            payload["unit_id"] = str(unit_id) if not isinstance(unit_id, str) else unit_id

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
        experiment_type: str | None = None,
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
        if experiment_type:
            payload["experiment_type"] = experiment_type
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

    def set_prompt_override(
        self,
        mask_id: str,
        prompt_name: str,
        value: str,
        project_id: str = "default",
        env: str = "prod",
        variant: str = "default",
        created_by: str | None = None,
    ) -> dict[str, Any] | None:
        """
        Set an override for a prompt by its name.

        This is a simplified API for optimizers - the service looks up
        which config key has this prompt name and sets the override.

        Returns dict with value_id and key, or None on failure.
        """
        payload: dict[str, Any] = {
            "project_id": project_id,
            "env": env,
            "mask_id": mask_id,
            "prompt_name": prompt_name,
            "value": value,
            "variant": variant,
        }
        if created_by:
            payload["created_by"] = created_by

        try:
            response = self._session.post(
                f"{self.base_url}/v1/config/prompts/override",
                json=payload,
                timeout=self.timeout,
            )
            response.raise_for_status()
            return response.json()
        except requests.RequestException:
            return None

    def commit_experiment_prompt(
        self,
        mask_id: str,
        prompt_name: str,
        project_id: str = "default",
        env: str = "prod",
        variant: str = "default",
        metadata: dict[str, Any] | None = None,
    ) -> dict[str, Any] | None:
        """
        Commit experiment prompt variant to Opik as permanent version.

        Args:
            mask_id: Experiment/optimization run ID
            prompt_name: Name of the prompt (e.g., "Researcher System Prompt")
            project_id: Project identifier
            env: Environment
            variant: Variant to commit (default "default")
            metadata: Optional metadata to include in the Opik version

        Returns dict with prompt_name, commit, opik_prompt_id, opik_version_id,
        or None on failure.
        """
        payload: dict[str, Any] = {
            "project_id": project_id,
            "env": env,
            "mask_id": mask_id,
            "prompt_name": prompt_name,
            "variant": variant,
        }
        if metadata:
            payload["metadata"] = metadata

        try:
            response = self._session.post(
                f"{self.base_url}/v1/config/prompts/commit",
                json=payload,
                timeout=self.timeout,
            )
            response.raise_for_status()
            return response.json()
        except requests.RequestException:
            return None

    def get_prompt_version_info(
        self,
        prompt_name: str,
        project_id: str = "default",
    ) -> dict[str, Any] | None:
        """
        Get Opik version info for a prompt.

        Returns dict with prompt_name, commit, opik_prompt_id, opik_version_id,
        or None if not found.
        """
        try:
            response = self._session.get(
                f"{self.base_url}/v1/config/prompts/versions/{prompt_name}",
                params={"project_id": project_id},
                timeout=self.timeout,
            )
            response.raise_for_status()
            return response.json()
        except requests.RequestException:
            return None

    def sync_prompts_to_opik(
        self,
        project_id: str = "default",
        env: str = "prod",
    ) -> dict[str, Any] | None:
        """
        Sync all registered prompts to Opik.

        Returns dict with synced list containing prompt_name, commit, and action,
        or None on failure.
        """
        payload: dict[str, Any] = {
            "project_id": project_id,
            "env": env,
        }

        try:
            response = self._session.post(
                f"{self.base_url}/v1/config/prompts/sync",
                json=payload,
                timeout=self.timeout,
            )
            response.raise_for_status()
            return response.json()
        except requests.RequestException:
            return None
