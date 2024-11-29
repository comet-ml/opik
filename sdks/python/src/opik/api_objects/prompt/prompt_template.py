from typing import Set
from opik import exceptions

import re

class PromptTemplate:
    def __init__(self, template: str) -> None:
        self._template = template
        self._placeholders = _extract_prompt_variable_keys(template)

    def format(self, **kwargs) -> str:
        template = self._template
        placeholders = self._placeholders

        kwargs_keys = set(kwargs.keys())

        if kwargs_keys != placeholders:
            raise exceptions.PromptPlaceholdersDontMatchFormatArguments(
                prompt_placeholders=placeholders,
                format_arguments=kwargs_keys
            )

        for key, value in kwargs.items():
            template = template.replace(f"{{{{{key}}}}}", str(value))

        return template

    def __str__(self) -> str:
        return self._template  


def _extract_prompt_variable_keys(prompt_template: str) -> Set[str]:
    pattern = r"\{\{(.*?)\}\}"
    return set(re.findall(pattern, prompt_template))
