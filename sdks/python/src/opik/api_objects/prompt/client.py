from typing import Any, Dict, List, Optional

from opik.rest_api import client as rest_client
from opik.rest_api import core as rest_api_core
from opik.rest_api.types import prompt_version_detail, PromptVersionDetailType

from . import prompt as opik_prompt
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
            or prompt_version.type != type.value
        ):
            prompt_version = self._create_new_version(
                name=name, prompt=prompt, type=type, metadata=metadata
            )

        prompt_obj = opik_prompt.Prompt.from_fern_prompt_version(
            name=name, prompt_version=prompt_version
        )

        return prompt_obj

    def _create_new_version(
        self,
        name: str,
        prompt: str,
        type: PromptVersionDetailType,
        metadata: Optional[Dict[str, Any]],
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

    # TODO: Need to add support for prompt name in the BE so we don't
    # need to retrieve the prompt id
    def get_all_prompts(self, name: str) -> List[opik_prompt.Prompt]:
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

            page = 1
            size = 100

            prompts: List[opik_prompt.Prompt] = []
            while True:
                prompt_versions = self._rest_client.prompts.get_prompt_versions(
                    id=prompt_id, page=page, size=size
                ).content
                prompts.extend(
                    [
                        opik_prompt.Prompt.from_fern_prompt_version(name, version)
                        for version in prompt_versions
                    ]
                )
                if len(prompt_versions) < size:
                    break
                page += 1

            return prompts

        except rest_api_core.ApiError as e:
            if e.status_code != 404:
                raise e

        return []
