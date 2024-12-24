from typing import Any, Dict, Optional

from opik.rest_api import client as rest_client
from opik.rest_api import core as rest_api_core
from opik.rest_api.types import prompt_version_detail

from . import prompt as opik_prompt


class PromptClient:
    def __init__(self, client: rest_client.OpikApi):
        self._rest_client = client

    def create_prompt(
        self,
        name: str,
        prompt: str,
        metadata: Optional[Dict[str, Any]],
    ) -> opik_prompt.Prompt:
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
        ):
            prompt_version = self._create_new_version(
                name=name, prompt=prompt, metadata=metadata
            )

        prompt_obj = opik_prompt.Prompt.from_fern_prompt_version(
            name=name, prompt_version=prompt_version
        )

        return prompt_obj

    def _create_new_version(
        self,
        name: str,
        prompt: str,
        metadata: Optional[Dict[str, Any]],
    ) -> prompt_version_detail.PromptVersionDetail:
        new_prompt_version_detail_data = prompt_version_detail.PromptVersionDetail(
            template=prompt,
            metadata=metadata,
        )
        new_prompt_version_detail: prompt_version_detail.PromptVersionDetail = (
            self._rest_client.prompts.create_prompt_version(
                name=name,
                version=new_prompt_version_detail_data,
            )
        )
        return new_prompt_version_detail

    def _get_latest_version(
        self, name: str
    ) -> Optional[prompt_version_detail.PromptVersionDetail]:
        try:
            prompt_latest_version = self._rest_client.prompts.retrieve_prompt_version(
                name=name
            )
            return prompt_latest_version
        except rest_api_core.ApiError as e:
            if e.status_code != 404:
                raise e
            return None

    def get_prompt(
        self,
        name: str,
        commit: Optional[str] = None,
    ) -> Optional[opik_prompt.Prompt]:
        """
        Retrieve the prompt detail for a given prompt name and commit version.

        Parameters:
            name: The name of the prompt.
            commit: An optional commit version of the prompt. If not provided, the latest version is retrieved.

        Returns:
            Prompt: The details of the specified prompt.
        """
        try:
            prompt_version = self._rest_client.prompts.retrieve_prompt_version(
                name=name,
                commit=commit,
            )
            prompt_obj = opik_prompt.Prompt.from_fern_prompt_version(
                name=name,
                prompt_version=prompt_version,
            )

            return prompt_obj

        except rest_api_core.ApiError as e:
            if e.status_code != 404:
                raise e

        return None
