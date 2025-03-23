import re
from typing import Any, Set
import jinja2

from opik import exceptions
from opik.rest_api.types import PromptVersionDetailType


class PromptTemplate:
    def __init__(
        self,
        template: str,
        validate_placeholders: bool = True,
        type: PromptVersionDetailType = "mustache",
    ) -> None:
        self._template = template
        self._type = type
        self._validate_placeholders = validate_placeholders

    def format(self, **kwargs: Any) -> str:
        if self._type == "mustache":
            template = self._template
            placeholders = _extract_mustache_placeholder_keys(self._template)
            kwargs_keys: Set[str] = set(kwargs.keys())

            if kwargs_keys != placeholders and self._validate_placeholders:
                raise exceptions.PromptPlaceholdersDontMatchFormatArguments(
                    prompt_placeholders=placeholders, format_arguments=kwargs_keys
                )

            for key, value in kwargs.items():
                template = template.replace(f"{{{{{key}}}}}", str(value))

        elif self._type == "jinja2":
            template = jinja2.Template(self._template).render(**kwargs)

        return template

    def __str__(self) -> str:
        return self._template


def _extract_mustache_placeholder_keys(prompt_template: str) -> Set[str]:
    pattern = r"\{\{(.*?)\}\}"
    return set(re.findall(pattern, prompt_template))
