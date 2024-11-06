from typing import Optional

from opik import Prompt
from opik.rest_api import PromptVersionDetail, client as rest_client
from opik.rest_api.core import ApiError


class PromptClient:
    def __init__(self, client: rest_client.OpikApi):
        self._rest_client = client

    def create_prompt(
        self,
        name: str,
        prompt: str,
    ) -> Prompt:
        """
        Creates the prompt detail for the given prompt name and template.

        Parameters:
        - name: The name of the prompt.
        - prompt: The template content for the prompt.

        Returns:
        - A Prompt object for the provided prompt name and template.
        """
        prompt_version = self._get_latest_version(name)

        if prompt_version is None or prompt_version.template != prompt:
            prompt_version = self._create_new_version(name=name, prompt=prompt)

        prompt_obj = Prompt.from_fern_prompt_version(
            name=name, prompt_version=prompt_version
        )

        return prompt_obj

    def _create_new_version(
        self,
        name: str,
        prompt: str,
    ) -> PromptVersionDetail:
        new_prompt_version_detail_data = PromptVersionDetail(template=prompt)
        new_prompt_version_detail: PromptVersionDetail = (
            self._rest_client.prompts.create_prompt_version(
                name=name,
                version=new_prompt_version_detail_data,
            )
        )
        return new_prompt_version_detail

    def _get_latest_version(self, name: str) -> Optional[PromptVersionDetail]:
        try:
            prompt_latest_version = self._rest_client.prompts.retrieve_prompt_version(
                name=name
            )
            return prompt_latest_version
        except ApiError as e:
            if e.status_code != 404:
                raise e
            return None

    def get_prompt(
        self,
        name: str,
        commit: Optional[str] = None,
    ) -> Optional[Prompt]:
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
            prompt_obj = Prompt.from_fern_prompt_version(
                name=name, prompt_version=prompt_version
            )

            return prompt_obj

        except ApiError as e:
            if e.status_code != 404:
                raise e

        return None
