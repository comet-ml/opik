import re
from typing import Any, List, Set

from opik.rest_api import PromptVersionDetail
from opik import exceptions

class Prompt:
    """
    Prompt class represents a prompt with a name, prompt text/template and commit hash.
    """

    def __init__(
        self,
        name: str,
        prompt: str,
    ) -> None:
        """
        Initializes a new instance of the class with the given parameters.
        Creates a new prompt using the opik client and sets the initial state of the instance attributes based on the created prompt.

        Parameters:
            name: The name for the prompt.
            prompt: The template for the prompt.
        """
        # we will import opik client here to avoid circular import issue
        from opik.api_objects import opik_client

        client = opik_client.get_client_cached()

        new_instance = client.create_prompt(
            name=name,
            prompt=prompt,
        )
        self._name = new_instance.name
        self._prompt = new_instance.prompt
        self._prompt_placeholders = _extract_prompt_variable_keys(self._prompt)
        self._commit = new_instance.commit
        self.__internal_api__version_id__: str = (
            new_instance.__internal_api__version_id__
        )
        self.__internal_api__prompt_id__: str = new_instance.__internal_api__prompt_id__

    @property
    def name(self) -> str:
        """The name of the prompt."""
        return self._name

    @property
    def prompt(self) -> str:
        """The latest template of the prompt."""
        return self._prompt

    @property
    def commit(self) -> str:
        """The commit hash of the prompt."""
        return self._commit

    def format(self, **kwargs: Any) -> str:
        """
        Replaces placeholders in the template with provided keyword arguments.

        Args:
            **kwargs: Arbitrary keyword arguments where the key represents the placeholder
                      in the template and the value is the value to replace the placeholder with.

        Returns:
            A string with all placeholders replaced by their corresponding values from kwargs.
        """
        template = self._prompt
        kwargs_keys = set(kwargs.keys())
        if kwargs_keys != self._prompt_placeholders:
            raise exceptions.PromptPlaceholdersDontMatchFormatArguments(prompt_placeholders=self._prompt_placeholders, format_arguments=kwargs_keys)

        for key, value in kwargs.items():
            template = template.replace(f"{{{{{key}}}}}", str(value))
        return template

    @classmethod
    def from_fern_prompt_version(
        cls,
        name: str,
        prompt_version: PromptVersionDetail,
    ) -> "Prompt":
        # will not call __init__ to avoid API calls, create new instance with __new__
        prompt = cls.__new__(cls)

        prompt.__internal_api__version_id__ = prompt_version.id
        prompt.__internal_api__prompt_id__ = prompt_version.prompt_id
        prompt._name = name
        prompt._prompt = prompt_version.template
        prompt._commit = prompt_version.commit

        return prompt


def _extract_prompt_variable_keys(prompt_template: str) -> Set[str]:
    pattern = r"\{\{(.*?)\}\}"
    return set(re.findall(pattern, prompt_template))