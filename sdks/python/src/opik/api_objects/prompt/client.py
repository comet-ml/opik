from typing import Any, Dict, List, Optional, Tuple
import json
import dataclasses

import opik.exceptions
from opik.rest_api import client as rest_client, PromptVersionUpdate
from opik.rest_api import core as rest_api_core
from opik.rest_api.types import prompt_version_detail
from opik.api_objects import opik_query_language
from . import types as prompt_types


@dataclasses.dataclass
class PromptSearchResult:
    """Result from searching prompts, containing name, template structure, and latest version details."""

    name: str
    template_structure: str
    prompt_version_detail: prompt_version_detail.PromptVersionDetail


class PromptClient:
    def __init__(self, client: rest_client.OpikApi):
        self._rest_client = client

    def create_prompt(
        self,
        name: str,
        prompt: str,
        metadata: Optional[Dict[str, Any]],
        type: prompt_types.PromptType = prompt_types.PromptType.MUSTACHE,
        template_structure: str = "text",
        id: Optional[str] = None,
        description: Optional[str] = None,
        change_description: Optional[str] = None,
        tags: Optional[List[str]] = None,
    ) -> prompt_version_detail.PromptVersionDetail:
        """
        Creates the prompt detail for the given prompt name and template.

        Parameters:
        - name: The name of the prompt.
        - prompt: The template content for the prompt.
        - metadata: Optional metadata for the prompt.
        - type: The template type (MUSTACHE or JINJA2).
        - template_structure: Either "text" (default) or "chat".
        - id: Optional unique identifier (UUID) for the prompt.
        - description: Optional description of the prompt (up to 255 characters).
        - change_description: Optional description of changes in this version.
        - tags: Optional list of tags to associate with the prompt.

        Returns:
        - A Prompt object for the provided prompt name and template.

        Raises:
        - PromptTemplateStructureMismatch: If a prompt with the same name already exists but has a different
          template_structure (e.g., trying to create a text prompt when a chat prompt exists, or vice versa).
          Template structure is immutable after prompt creation.
        """
        prompt_version = self._get_latest_version(name)

        # For chat prompts, compare parsed JSON to avoid formatting differences
        templates_equal = False

        if prompt_version is not None:
            if prompt_version.template_structure != template_structure:
                raise opik.exceptions.PromptTemplateStructureMismatch(
                    prompt_name=name,
                    existing_structure=prompt_version.template_structure,
                    attempted_structure=template_structure,
                )

            if template_structure == "chat":
                try:
                    existing_messages = json.loads(prompt_version.template)
                    new_messages = json.loads(prompt)
                    templates_equal = existing_messages == new_messages
                except (json.JSONDecodeError, TypeError):
                    templates_equal = prompt_version.template == prompt
            else:
                templates_equal = prompt_version.template == prompt

        # Create a new version if:
        # - No version exists yet (new prompt)
        # - Template content has changed
        # - Metadata has changed
        # - Type has changed
        # Note: template_structure is immutable and used by the backend only if it is the first prompt version.
        if (
            prompt_version is None
            or not templates_equal
            or prompt_version.metadata != metadata
            or prompt_version.type != type.value
        ):
            prompt_version = self._create_new_version(
                name=name,
                prompt=prompt,
                type=type,
                metadata=metadata,
                template_structure=template_structure,
                id=id,
                description=description,
                change_description=change_description,
                tags=tags,
            )

        return prompt_version

    def _create_new_version(
        self,
        name: str,
        prompt: str,
        type: prompt_version_detail.PromptVersionDetailType,
        metadata: Optional[Dict[str, Any]],
        template_structure: str = "text",
        id: Optional[str] = None,
        description: Optional[str] = None,
        change_description: Optional[str] = None,
        tags: Optional[List[str]] = None,
    ) -> prompt_version_detail.PromptVersionDetail:
        # Check if this is a new prompt (no existing versions)
        existing_version = self._get_latest_version(name)

        # If it's a new prompt and container-level params are provided, use create_prompt endpoint
        # which creates both the container and first version in one call
        if existing_version is None and (
            id is not None or description is not None or tags is not None
        ):
            self._rest_client.prompts.create_prompt(
                name=name,
                id=id,
                description=description,
                template=prompt,
                metadata=metadata,
                change_description=change_description,
                type=type,
                template_structure=template_structure,
                tags=tags,
            )
            # After creating, retrieve the version that was created
            new_prompt_version_detail = (
                self._rest_client.prompts.retrieve_prompt_version(
                    name=name,
                )
            )
            # retrieve_prompt_version may not return tags, so we need to set them manually
            # from the tags we just passed to create_prompt
            if tags is not None and new_prompt_version_detail.tags is None:
                # Pydantic objects are frozen, so we need to create a new object with tags
                new_prompt_version_detail = prompt_version_detail.PromptVersionDetail(
                    id=new_prompt_version_detail.id,
                    prompt_id=new_prompt_version_detail.prompt_id,
                    commit=new_prompt_version_detail.commit,
                    template=new_prompt_version_detail.template,
                    metadata=new_prompt_version_detail.metadata,
                    type=new_prompt_version_detail.type,
                    change_description=new_prompt_version_detail.change_description,
                    tags=tags,
                    variables=new_prompt_version_detail.variables,
                    template_structure=new_prompt_version_detail.template_structure,
                    created_at=new_prompt_version_detail.created_at,
                    created_by=new_prompt_version_detail.created_by,
                )
        else:
            # For existing prompts or when no container-level params, use create_prompt_version
            new_prompt_version_detail_data = prompt_version_detail.PromptVersionDetail(
                template=prompt,
                metadata=metadata,
                type=type,
            )
            new_prompt_version_detail = self._rest_client.prompts.create_prompt_version(
                name=name,
                version=new_prompt_version_detail_data,
                template_structure=template_structure,
            )
        return new_prompt_version_detail

    def _get_latest_version(
        self, name: str
    ) -> Optional[prompt_version_detail.PromptVersionDetail]:
        return self.get_prompt(name=name, commit=None)

    def get_prompt(
        self,
        name: str,
        commit: Optional[str] = None,
        raise_if_not_template_structure: Optional[str] = None,
    ) -> Optional[prompt_version_detail.PromptVersionDetail]:
        """
        Retrieve the prompt detail for a given prompt name and commit version.

        Parameters:
            name: The name of the prompt.
            commit: An optional commit version of the prompt. If not provided, the latest version is retrieved.
            raise_if_not_template_structure: Optional template structure validation. If provided and doesn't match, raises PromptTemplateStructureMismatch.

        Returns:
            Prompt: The details of the specified prompt.
        """
        try:
            prompt_version = self._rest_client.prompts.retrieve_prompt_version(
                name=name,
                commit=commit,
            )

            should_skip_validation = (
                prompt_version.template_structure is None
                and raise_if_not_template_structure == "text"
            )
            if should_skip_validation:
                return prompt_version

            # Client-side validation for template_structure if requested and not skipped
            if (
                raise_if_not_template_structure is not None
                and prompt_version.template_structure != raise_if_not_template_structure
            ):
                raise opik.exceptions.PromptTemplateStructureMismatch(
                    prompt_name=name,
                    existing_structure=prompt_version.template_structure,
                    attempted_structure=raise_if_not_template_structure,
                )

            return prompt_version
        except rest_api_core.ApiError as e:
            if e.status_code != 404:
                raise e
            # 400, 404 - not found
        return None

    # TODO: Need to add support for prompt name in the BE so we don't
    # need to retrieve the prompt id
    def get_all_prompt_versions(
        self,
        name: str,
        search: Optional[str] = None,
        filter_string: Optional[str] = None,
    ) -> List[prompt_version_detail.PromptVersionDetail]:
        """
        Retrieve all the prompt details for a given prompt name.

        Parameters:
            name: The name of the prompt.
            search: Optional search text to find in template or change description fields.
            filter_string: A filter string to narrow down the search using Opik Query Language (OQL).
                The format is: "<COLUMN> <OPERATOR> <VALUE> [AND <COLUMN> <OPERATOR> <VALUE>]*"

                Supported columns include:
                - `id`, `commit`, `template`, `change_description`, `created_by`: String fields with full operator support
                - `metadata`: Dictionary field (use dot notation, e.g., "metadata.environment")
                - `type`: Enum field (=, != only)
                - `tags`: List field (use "contains" operator only)
                - `created_at`: DateTime field (use ISO 8601 format, e.g., "2024-01-01T00:00:00Z")

                Examples:
                - `tags contains "production"` - Filter by tag
                - `tags contains "v1" AND tags contains "production"` - Filter by multiple tags
                - `template contains "customer"` - Filter by template content
                - `created_by = "user@example.com"` - Filter by creator
                - `created_at >= "2024-01-01T00:00:00Z"` - Filter by creation date
                - `metadata.environment = "prod"` - Filter by metadata field

        Returns:
            List[PromptVersionDetail]: A list of prompt versions for the given name.

        Example:
            # Get all versions of a prompt
            versions = prompt_client.get_all_prompt_versions(name="my-prompt")

            # Filter by tags (versions containing "production" tag)
            versions = prompt_client.get_all_prompt_versions(
                name="my-prompt",
                filter_string='tags contains "production"'
            )

            # Search for specific text in template or change description fields
            versions = prompt_client.get_all_prompt_versions(
                name="my-prompt",
                search="customer"
            )

            # Combine search and filtering
            versions = prompt_client.get_all_prompt_versions(
                name="my-prompt",
                search="customer",
                filter_string='tags contains "production"'
            )
        """
        try:
            prompts_matching_name_string = self._rest_client.prompts.get_prompts(
                name=name
            ).content
            if (
                prompts_matching_name_string is None
                or len(prompts_matching_name_string) == 0
            ):
                raise ValueError("No prompts found for name: " + name)

            filtered_prompt_list = [
                x.id for x in prompts_matching_name_string if name == x.name
            ]
            if len(filtered_prompt_list) == 0:
                raise ValueError("No prompts found for name: " + name)

            filters: Optional[str] = None
            if filter_string:
                oql = opik_query_language.OpikQueryLanguage.for_prompt_versions(
                    filter_string
                )
                filters = oql.parsed_filters

            prompt_id = filtered_prompt_list[0]
            return self._get_prompt_versions_by_id_paginated(
                prompt_id, search=search, filters=filters
            )

        except rest_api_core.ApiError as e:
            if e.status_code != 404:
                raise e

        return []

    def _get_prompt_versions_by_id_paginated(
        self,
        prompt_id: str,
        search: Optional[str] = None,
        filters: Optional[str] = None,
    ) -> List[prompt_version_detail.PromptVersionDetail]:
        page = 1
        size = 100
        prompts: List[prompt_version_detail.PromptVersionDetail] = []
        while True:
            prompt_versions_page = self._rest_client.prompts.get_prompt_versions(
                id=prompt_id,
                page=page,
                size=size,
                search=search,
                filters=filters,
            ).content

            versions = prompt_versions_page or []
            prompts.extend(
                [
                    # Converting to PromptVersionDetail for consistency with other methods.
                    # TODO: backend should implement non-frontend endpoint which will return PromptVersionDetail objects
                    prompt_version_detail.PromptVersionDetail(
                        id=version.id,
                        prompt_id=version.prompt_id,
                        template=version.template,
                        type=version.type,
                        metadata=version.metadata,
                        commit=version.commit,
                        created_at=version.created_at,
                        created_by=version.created_by,
                        tags=version.tags,
                    )
                    for version in versions
                ]
            )

            if len(versions) < size:
                break
            page += 1

        return prompts

    def search_prompts(
        self,
        *,
        name: Optional[str] = None,
        parsed_filters: Optional[List[Dict[str, Any]]] = None,
    ) -> List[PromptSearchResult]:
        """
        Search prompt containers by optional name substring and filters, then
        return the latest version detail for each matched prompt container.

        Parameters:
            name: Optional substring of the prompt name to search for.
            parsed_filters: List of parsed filters (OQL) that will be stringified for the backend.

        Returns:
            List[PromptSearchResult]: Each result contains name, template_structure, and prompt_version_detail.
        """
        try:
            filters_str = (
                json.dumps(parsed_filters) if parsed_filters is not None else None
            )

            # Page through all prompt containers and collect:
            # (name, template_structure, tags)
            page = 1
            size = 1000
            prompt_info: List[Tuple[str, str, Optional[List[str]]]] = []
            while True:
                prompts_page = self._rest_client.prompts.get_prompts(
                    page=page,
                    size=size,
                    name=name,
                    filters=filters_str,
                )
                content = prompts_page.content or []
                if len(content) == 0:
                    break
                prompt_info.extend(
                    [(p.name, p.template_structure or "text", p.tags) for p in content]
                )
                if len(content) < size:
                    break
                page += 1

            if len(prompt_info) == 0:
                return []

            # Retrieve latest version for each container name
            results: List[PromptSearchResult] = []
            for prompt_name, template_structure, tags in prompt_info:
                try:
                    latest_version = self._rest_client.prompts.retrieve_prompt_version(
                        name=prompt_name,
                    )
                    # retrieve_prompt_version may not return tags, so we need to set them from get_prompts response
                    if tags is not None and latest_version.tags is None:
                        # Pydantic objects are frozen, so we need to create a new object with tags
                        latest_version = prompt_version_detail.PromptVersionDetail(
                            id=latest_version.id,
                            prompt_id=latest_version.prompt_id,
                            commit=latest_version.commit,
                            template=latest_version.template,
                            metadata=latest_version.metadata,
                            type=latest_version.type,
                            change_description=latest_version.change_description,
                            tags=tags,
                            variables=latest_version.variables,
                            template_structure=latest_version.template_structure,
                            created_at=latest_version.created_at,
                            created_by=latest_version.created_by,
                        )
                    results.append(
                        PromptSearchResult(
                            name=prompt_name,
                            template_structure=template_structure,
                            prompt_version_detail=latest_version,
                        )
                    )
                except rest_api_core.ApiError as e:
                    # Skip prompts that can't be retrieved (e.g., deleted between search and retrieval)
                    if e.status_code == 404:
                        continue
                    raise e

            return results

        except rest_api_core.ApiError as e:
            if e.status_code != 404:
                raise e
            return []

    def batch_update_prompt_version_tags(
        self,
        version_ids: List[str],
        tags: Optional[List[str]] = None,
        merge: Optional[bool] = None,
    ) -> None:
        """
        Update tags for one or more prompt versions in a single batch operation.

        Parameters:
            version_ids: List of prompt version IDs to update.
            tags: Tags to set or merge. Semantics:
                - None: No change to tags (preserves existing tags).
                - []: Clear all tags (when merge is False or None).
                - ['tag1', 'tag2']: Set or merge tags (based on merge parameter).
            merge: Controls tag update behavior. Semantics:
                - None: Use backend default behavior (replace mode).
                - False: Replace all existing tags (replace mode).
                - True: Merge new tags with existing tags (union).

        Example:
            # Replace tags on multiple versions (default behavior)
            prompts_client.batch_update_prompt_version_tags(
                version_ids=["version-id-1", "version-id-2"],
                tags=["production", "v2"]
            )

            # Merge new tags with existing tags
            prompts_client.batch_update_prompt_version_tags(
                version_ids=["version-id-1"],
                tags=["hotfix"],
                merge=True
            )

            # Clear all tags
            prompts_client.batch_update_prompt_version_tags(
                version_ids=["version-id-1"],
                tags=[]
            )
        """
        update = PromptVersionUpdate(tags=tags)
        self._rest_client.prompts.update_prompt_versions(
            ids=version_ids, update=update, merge_tags=merge
        )
