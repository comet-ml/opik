from typing import Any, Dict, List, Optional, Tuple
import json
import dataclasses

import opik.exceptions
from opik.rest_api import client as rest_client
from opik.rest_api import core as rest_api_core
from opik.rest_api.types import prompt_version_detail
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
    ) -> prompt_version_detail.PromptVersionDetail:
        """
        Creates the prompt detail for the given prompt name and template.

        Parameters:
        - name: The name of the prompt.
        - prompt: The template content for the prompt.
        - metadata: Optional metadata for the prompt.
        - type: The template type (MUSTACHE or JINJA2).
        - template_structure: Either "text" (default) or "chat".

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
            )

        return prompt_version

    def _create_new_version(
        self,
        name: str,
        prompt: str,
        type: prompt_version_detail.PromptVersionDetailType,
        metadata: Optional[Dict[str, Any]],
        template_structure: str = "text",
    ) -> prompt_version_detail.PromptVersionDetail:
        new_prompt_version_detail_data = prompt_version_detail.PromptVersionDetail(
            template=prompt,
            metadata=metadata,
            type=type,
        )
        new_prompt_version_detail: prompt_version_detail.PromptVersionDetail = (
            self._rest_client.prompts.create_prompt_version(
                name=name,
                version=new_prompt_version_detail_data,
                template_structure=template_structure,
            )
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
        self, name: str
    ) -> List[prompt_version_detail.PromptVersionDetail]:
        """
        Retrieve all the prompt details for a given prompt name.

        Parameters:
            name: The name of the prompt.

        Returns:
            List[Prompt]: A list of prompts for the given name.
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

            prompt_id = filtered_prompt_list[0]
            return self._get_prompt_versions_by_id_paginated(prompt_id)

        except rest_api_core.ApiError as e:
            if e.status_code != 404:
                raise e

        return []

    def _get_prompt_versions_by_id_paginated(
        self, prompt_id: str
    ) -> List[prompt_version_detail.PromptVersionDetail]:
        page = 1
        size = 100
        prompts: List[prompt_version_detail.PromptVersionDetail] = []
        while True:
            prompt_versions_page = self._rest_client.prompts.get_prompt_versions(
                id=prompt_id, page=page, size=size
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

            # Page through all prompt containers and collect name + template_structure
            page = 1
            size = 1000
            prompt_info: List[Tuple[str, str]] = []  # (name, template_structure)
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
                    [(p.name, p.template_structure or "text") for p in content]
                )
                if len(content) < size:
                    break
                page += 1

            if len(prompt_info) == 0:
                return []

            # Retrieve latest version for each container name
            results: List[PromptSearchResult] = []
            for prompt_name, template_structure in prompt_info:
                try:
                    latest_version = self._rest_client.prompts.retrieve_prompt_version(
                        name=prompt_name,
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
