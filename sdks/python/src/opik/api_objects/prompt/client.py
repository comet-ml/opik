from typing import Optional

from opik import Prompt
from opik.api_objects.helpers import generate_id
from opik.rest_api import PromptDetail, PromptVersionDetail, client as rest_client
from opik.rest_api.core import ApiError


class PromptClient:
    def __init__(self, client: rest_client.OpikApi):
        self._rest_client = client

    def create_prompt(
        self,
        name: str,
        prompt: str,
    ) -> Prompt:
        try:
            prompt_id = generate_id()

            self._rest_client.prompts.create_prompt(
                id=prompt_id,
                name=name,
                template=prompt,
            )

            prompt_detail = self._rest_client.prompts.get_prompt_by_id(id=prompt_id)

            prompt_obj: Prompt = Prompt.from_fern_prompt_detail(prompt_detail)

            return prompt_obj

        except ApiError as e:
            if e.status_code != 409:
                raise e

        # IF E.STATUS_CODE == 409 --> PROMPT EXISTS, WE NEED TO CREATE A NEW VERSION
        prompt_detail = self._create_prompt_detail(name, prompt)
        prompt_obj = Prompt.from_fern_prompt_detail(prompt_detail)

        return prompt_obj

    def _create_prompt_detail(self, name: str, prompt: str) -> PromptDetail:
        """
        Creates or updates the prompt detail for the given prompt name and template.

        Parameters:
        - name: The name of the prompt.
        - prompt: The template content for the prompt.

        Returns:
        - A PromptDetail object for the provided prompt name and template.
        """
        prompt_detail = self._get_latest_version(name)

        # IF TEMPLATES ARE EQUAL -> RETURN LATEST VERSION
        if prompt_detail.latest_version.template == prompt:
            return prompt_detail

        return self._create_new_version(
            name=name, prompt=prompt, prompt_id=prompt_detail.id
        )

    def _create_new_version(
        self,
        name: str,
        prompt: str,
        prompt_id: str,
    ) -> PromptDetail:
        new_prompt_version_detail_data = PromptVersionDetail(
            prompt_id=prompt_id,
            template=prompt,
        )
        new_prompt_version_detail: PromptVersionDetail = (
            self._rest_client.prompts.create_prompt_version(
                name=name,
                version=new_prompt_version_detail_data,
            )
        )
        prompt_detail = self._rest_client.prompts.get_prompt_by_id(
            id=new_prompt_version_detail.prompt_id
        )
        return prompt_detail

    def _get_latest_version(self, name: str) -> PromptDetail:
        prompt_latest_version: PromptVersionDetail = (
            self._rest_client.prompts.retrieve_prompt_version(name=name)
        )
        prompt_detail: PromptDetail = self._rest_client.prompts.get_prompt_by_id(
            id=prompt_latest_version.prompt_id
        )
        return prompt_detail

    def get_prompt(
        self,
        name: str,
        commit: Optional[str] = None,
    ) -> Prompt:
        """
        Retrieve the prompt detail for a given prompt name and commit version.

        Parameters:
            name: The name of the prompt.
            commit: An optional commit version of the prompt. If not provided, the latest version is retrieved.

        Returns:
            Prompt: The details of the specified prompt.
        """
        prompt_version: PromptVersionDetail = (
            self._rest_client.prompts.retrieve_prompt_version(
                name=name,
                commit=commit,
            )
        )

        prompt_detail: PromptDetail = self._rest_client.prompts.get_prompt_by_id(
            id=prompt_version.prompt_id
        )
        prompt_obj = Prompt.from_fern_prompt_detail(prompt_detail, prompt_version)

        return prompt_obj