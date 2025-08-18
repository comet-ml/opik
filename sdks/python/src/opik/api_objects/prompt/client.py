import json
import re
from typing import Any, Dict, List, Optional

from opik.rest_api import client as rest_client
from opik.rest_api import core as rest_api_core
from opik.rest_api.types import prompt_version_detail, PromptVersionDetailType

from .prompt import PromptType


class PromptClient:
    def __init__(self, client: rest_client.OpikApi):
        self._rest_client = client

    def create_prompt(
        self,
        name: str,
        prompt: str,
        metadata: Optional[Dict[str, Any]],
        type: PromptType = PromptType.MUSTACHE,
    ) -> prompt_version_detail.PromptVersionDetail:
        """
        Creates the prompt detail for the given prompt name and template.

        Parameters:
        - name: The name of the prompt.
        - prompt: The template content for the prompt.

        Returns:
        - A Prompt object for the provided prompt name and template.
        """
        prompt_version = self._get_latest_version(name)

        if (
            prompt_version is None
            or prompt_version.template != prompt
            or prompt_version.metadata != metadata
            or prompt_version.type != type.value
        ):
            prompt_version = self._create_new_version(
                name=name, prompt=prompt, type=type, metadata=metadata
            )

        return prompt_version

    def _create_new_version(
        self,
        name: str,
        prompt: str,
        type: PromptVersionDetailType,
        metadata: Optional[Dict[str, Any]],
    ) -> prompt_version_detail.PromptVersionDetail:
        """
        Creates a new version of the prompt.

        Parameters:
        - name: The name of the prompt.
        - prompt: The template content for the prompt.
        - type: The type of the prompt.
        - metadata: Optional metadata to be included in the prompt.

        Returns:
        - A PromptVersionDetail object for the new version.
        """
        create_prompt_version = rest_api_core.CreatePromptVersion(
            name=name,
            prompt=prompt,
            type=type,
            metadata=metadata,
        )

        return self._rest_client.prompts.create_prompt_version(create_prompt_version)

    def _get_latest_version(self, name: str) -> Optional[prompt_version_detail.PromptVersionDetail]:
        """
        Gets the latest version of the prompt.

        Parameters:
        - name: The name of the prompt.

        Returns:
        - A PromptVersionDetail object for the latest version, or None if not found.
        """
        try:
            return self._rest_client.prompts.get_prompt(name=name)
        except Exception:
            return None

    def get_prompt(
        self, name: str, commit: Optional[str] = None
    ) -> Optional[prompt_version_detail.PromptVersionDetail]:
        """
        Gets the prompt detail for the given prompt name and commit version.

        Parameters:
        - name: The name of the prompt.
        - commit: An optional commit version of the prompt. If not provided, the latest version is retrieved.

        Returns:
        - A PromptVersionDetail object for the specified prompt, or None if not found.
        """
        try:
            return self._rest_client.prompts.get_prompt(name=name, commit=commit)
        except Exception:
            return None

    def get_all_prompts(self, name: str) -> List[prompt_version_detail.PromptVersionDetail]:
        """
        Gets all versions of the prompt.

        Parameters:
        - name: The name of the prompt.

        Returns:
        - A list of PromptVersionDetail objects for all versions of the prompt.
        """
        return self._rest_client.prompts.get_all_prompts(name=name)

    def get_prompts_with_filters(
        self,
        filters: Optional[str] = None,
        name: Optional[str] = None,
        page: int = 1,
        size: int = 100,
        get_latest_versions: bool = True,
    ) -> List[Dict[str, Any]]:
        """
        Retrieve prompts with filtering support.

        Parameters:
            filters: Optional filter string to narrow down the search. 
                    Example: 'tags contains "production"' or 'name contains "chatbot"'
            name: Optional prompt name filter
            page: Page number for pagination (default: 1)
            size: Page size for pagination (default: 100)
            get_latest_versions: Whether to fetch latest version details for each prompt (default: True)

        Returns:
            List[Dict]: A list of dictionaries containing prompt info and optionally latest version details.
                       Each dict has 'prompt_public' and 'latest_version' keys.
        """
        try:
            prompts_page = self._rest_client.prompts.get_prompts(
                filters=filters,
                name=name,
                page=page,
                size=size,
            )

            if not get_latest_versions:
                return [{"prompt_public": prompt, "latest_version": None} for prompt in prompts_page.content]

            # Get latest version details for each prompt
            result = []
            for prompt in prompts_page.content:
                latest_version = self._get_latest_version(prompt.name)
                result.append({
                    "prompt_public": prompt,
                    "latest_version": latest_version
                })

            return result

        except Exception as e:
            # Handle specific error cases
            if "Invalid filters" in str(e):
                raise ValueError(f"Invalid filter format: {filters}")
            raise e
