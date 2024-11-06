from typing import Any, Optional

from opik.rest_api import PromptDetail


class Prompt:
    def __init__(
        self,
        name: str,
        template: str,
        description: Optional[str],
    ) -> None:
        from opik.api_objects import opik_client

        client = opik_client.get_client_cached()

        new_instance = client.create_prompt(
            name=name,
            template=template,
            description=description,
        )
        self._name = new_instance.name
        self._template = new_instance.template
        self._description = new_instance.description
        self._commit = new_instance.commit
        self._id = new_instance.id

    @property
    def name(self) -> str:
        """The name of the prompt."""
        return self._name

    @property
    def template(self) -> str:
        """The latest template of the prompt."""
        return self._template

    @property
    def description(self) -> Optional[str]:
        """The description of the prompt."""
        return self._description

    @property
    def commit(self) -> str:
        """The commit hash of the prompt."""
        return self._commit

    @property
    def id(self) -> str:
        """The ID of the prompt."""
        return self._id

    def format(self, **kwargs: Any) -> str:
        """
        Replaces placeholders in the template with provided keyword arguments.

        Args:
            **kwargs: Arbitrary keyword arguments where the key represents the placeholder
                      in the template and the value is the value to replace the placeholder with.

        Returns:
            A string with all placeholders replaced by their corresponding values from kwargs.
        """
        template = self._template
        for key, value in kwargs.items():
            template = template.replace(f"{{{key}}}", str(value))
        return template

    @classmethod
    def from_fern_prompt_detail(cls, prompt_detail: PromptDetail) -> "Prompt":
        # will not call __init__ to avoid API calls, create new instance with __new__
        prompt = cls.__new__(cls)

        prompt._id = prompt_detail.id
        prompt._name = prompt_detail.name
        prompt._description = prompt_detail.description

        prompt._template = prompt_detail.latest_version.template
        prompt._commit = prompt_detail.latest_version.commit

        return prompt
