"""Bridge between config service prompts and Opik's versioning system."""

from __future__ import annotations

import logging
from typing import Any, TYPE_CHECKING

if TYPE_CHECKING:
    from opik.api_objects.prompt import client as prompt_client
    from .sqlite_store import SQLiteConfigStore

LOGGER = logging.getLogger(__name__)


class PromptBridge:
    """
    Bridges config service prompts with Opik's versioning system.

    Can operate in two modes:
    - Real mode: Uses actual Opik backend via PromptClient
    - Mock mode: Uses SQLite mock store for demos/POC
    """

    def __init__(
        self,
        config_store: "SQLiteConfigStore",
        opik_prompt_client: "prompt_client.PromptClient | None" = None,
        use_mock: bool = False,
    ) -> None:
        self.store = config_store
        self.opik = opik_prompt_client
        self.use_mock = use_mock or (opik_prompt_client is None)

    def _create_prompt_version(
        self,
        name: str,
        template: str,
        metadata: dict[str, Any] | None = None,
        change_description: str | None = None,
        created_by: str | None = None,
    ) -> dict[str, Any]:
        """Create a prompt version using either mock or real Opik."""
        if self.use_mock:
            result = self.store.mock_create_prompt(
                name=name,
                template=template,
                metadata=metadata,
                change_description=change_description,
                created_by=created_by,
            )
            # mock_create_prompt returns PromptWithLatestVersion shape
            latest = result.get("latest_version", {})
            return {
                "id": latest.get("id"),
                "prompt_id": result.get("id"),  # prompt ID is at top level
                "commit": latest.get("commit"),
                "template": latest.get("template"),
                "metadata": latest.get("metadata"),
            }
        else:
            if not self.opik:
                raise RuntimeError("Opik client not available and mock mode disabled")
            prompt_version = self.opik.create_prompt(
                name=name,
                prompt=template,
                metadata=metadata,
            )
            return {
                "id": prompt_version.id,
                "prompt_id": prompt_version.prompt_id,
                "commit": prompt_version.commit,
                "template": prompt_version.template,
                "metadata": prompt_version.metadata,
            }

    def _get_prompt_version(
        self,
        name: str,
        commit: str | None = None,
    ) -> dict[str, Any] | None:
        """Get a prompt version using either mock or real Opik."""
        if self.use_mock:
            return self.store.mock_get_prompt(name=name, commit=commit)
        else:
            if not self.opik:
                return None
            prompt_version = self.opik.get_prompt(name=name, commit=commit)
            if not prompt_version:
                return None
            return {
                "id": prompt_version.id,
                "prompt_id": prompt_version.prompt_id,
                "commit": prompt_version.commit,
                "template": prompt_version.template,
                "metadata": prompt_version.metadata,
            }

    def sync_prompt_to_opik(
        self,
        project_id: str,
        prompt_name: str,
        template: str,
        config_key: str,
        metadata: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """
        Ensure prompt exists in Opik. Creates version only if content changed.

        Returns: {prompt_name, prompt_id, version_id, commit, action: "created"|"unchanged"}
        """
        combined_metadata = metadata.copy() if metadata else {}
        combined_metadata["config_source"] = "opik_config"
        combined_metadata["config_key"] = config_key

        existing_mapping = self.store.get_prompt_mapping(project_id, prompt_name)

        try:
            result = self._create_prompt_version(
                name=prompt_name,
                template=template,
                metadata=combined_metadata,
            )

            action = "created"
            if existing_mapping and existing_mapping.get("latest_commit") == result["commit"]:
                action = "unchanged"

            self.store.register_prompt_mapping(
                project_id=project_id,
                config_key=config_key,
                prompt_name=prompt_name,
                opik_prompt_id=result["prompt_id"],
                commit=result["commit"],
                version_id=result["id"],
            )

            return {
                "prompt_name": prompt_name,
                "opik_prompt_id": result["prompt_id"],
                "opik_version_id": result["id"],
                "commit": result["commit"],
                "action": action,
            }

        except Exception as e:
            LOGGER.error(f"Failed to sync prompt '{prompt_name}': {e}")
            raise

    def commit_experiment_variant(
        self,
        project_id: str,
        env: str,
        mask_id: str,
        prompt_name: str,
        variant: str = "default",
        metadata: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """
        Commit experiment's winning variant to Opik as new version.

        Steps:
        1. Get current override value from mask_overrides
        2. Create new PromptVersion in Opik with metadata
        3. Update prompt_mappings with new commit
        4. Update published value to the new content

        Returns: {prompt_name, commit, opik_prompt_id, opik_version_id}
        """
        override_value = self.store.get_experiment_prompt_value(
            project_id=project_id,
            env=env,
            mask_id=mask_id,
            prompt_name=prompt_name,
            variant=variant,
        )

        if not override_value:
            raise ValueError(
                f"No override found for prompt '{prompt_name}' in experiment '{mask_id}'"
            )

        template = override_value.get("prompt")
        if not template:
            raise ValueError(f"Override for prompt '{prompt_name}' has no 'prompt' field")

        config_key = self.store.find_key_by_prompt_name(project_id, env, prompt_name)
        if not config_key:
            raise ValueError(f"No config key found for prompt '{prompt_name}'")

        combined_metadata = metadata.copy() if metadata else {}
        combined_metadata["config_source"] = "opik_config"
        combined_metadata["config_key"] = config_key
        combined_metadata["committed_from_experiment"] = mask_id
        combined_metadata["committed_from_variant"] = variant

        try:
            result = self._create_prompt_version(
                name=prompt_name,
                template=template,
                metadata=combined_metadata,
                change_description=f"Committed from experiment {mask_id}",
                created_by=f"optimizer:{mask_id}",
            )

            self.store.register_prompt_mapping(
                project_id=project_id,
                config_key=config_key,
                prompt_name=prompt_name,
                opik_prompt_id=result["prompt_id"],
                commit=result["commit"],
                version_id=result["id"],
            )

            self.store.publish_value(
                project_id=project_id,
                env=env,
                key=config_key,
                value={"prompt_name": prompt_name, "prompt": template},
                created_by=f"committed_from:{mask_id}",
            )

            return {
                "prompt_name": prompt_name,
                "commit": result["commit"],
                "opik_prompt_id": result["prompt_id"],
                "opik_version_id": result["id"],
            }

        except Exception as e:
            LOGGER.error(f"Failed to commit prompt '{prompt_name}': {e}")
            raise

    def get_version_for_trace(
        self,
        project_id: str,
        prompt_name: str,
    ) -> dict[str, Any] | None:
        """Get version info for trace metadata enrichment."""
        mapping = self.store.get_prompt_mapping(project_id, prompt_name)
        if not mapping:
            return None

        return {
            "commit": mapping.get("latest_commit"),
            "opik_prompt_id": mapping.get("opik_prompt_id"),
            "opik_version_id": mapping.get("latest_opik_version_id"),
        }

    def sync_all_prompts(
        self,
        project_id: str,
        env: str = "prod",
    ) -> list[dict[str, Any]]:
        """
        Sync all registered prompts to Opik (creates versions if changed).

        Returns: [{prompt_name, commit, action: "created"|"unchanged"|"skipped"}]
        """
        results: list[dict[str, Any]] = []
        published = self.store.list_published(project_id, env)

        for item in published:
            value = item.get("value")
            if not isinstance(value, dict):
                continue

            prompt_name = value.get("prompt_name")
            template = value.get("prompt")

            if not prompt_name or not template:
                continue

            config_key = item.get("key")
            if not config_key:
                continue

            try:
                result = self.sync_prompt_to_opik(
                    project_id=project_id,
                    prompt_name=prompt_name,
                    template=template,
                    config_key=config_key,
                )
                results.append(result)
            except Exception as e:
                LOGGER.warning(f"Skipped syncing prompt '{prompt_name}': {e}")
                results.append({
                    "prompt_name": prompt_name,
                    "action": "skipped",
                    "error": str(e),
                })

        return results
