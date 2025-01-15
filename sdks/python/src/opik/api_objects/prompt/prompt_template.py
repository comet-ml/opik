import re
from typing import Any, Set

from opik import exceptions


class PromptTemplate:
    def __init__(self, template: str, validate_placeholders: bool = True) -> None:
        self._template = template
        self._placeholders = _extract_placeholder_keys(template)
        self._validate_placeholders = validate_placeholders

    def format(self, **kwargs: Any) -> str:
        template = self._template
        placeholders = self._placeholders

        kwargs_keys: Set[str] = set(kwargs.keys())

        if kwargs_keys != placeholders and self._validate_placeholders:
            raise exceptions.PromptPlaceholdersDontMatchFormatArguments(
                prompt_placeholders=placeholders, format_arguments=kwargs_keys
            )

        for key, value in kwargs.items():
            template = template.replace(f"{{{{{key}}}}}", str(value))

        return template

    def __str__(self) -> str:
        return self._template


def _extract_placeholder_keys(prompt_template: str) -> Set[str]:
    pattern = r"\{\{(.*?)\}\}"
    return set(re.findall(pattern, prompt_template))
