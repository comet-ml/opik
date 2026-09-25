import re
from typing import Any, Set
from typing_extensions import override
import jinja2

import opik.exceptions as exceptions
from .. import types as prompt_types
from .. import base_prompt_template


class PromptTemplate(base_prompt_template.BasePromptTemplate):
    def __init__(
        self,
        template: str,
        validate_placeholders: bool = True,
        type: prompt_types.PromptType = prompt_types.PromptType.MUSTACHE,
    ) -> None:
        self._template = template
        self._type = type
        self._validate_placeholders = validate_placeholders

    @property
    def text(self) -> str:
        return self._template

    @override
    def format(self, **kwargs: Any) -> str:
        """Render the template with the given values.

        For mustache templates, every ``{{key}}`` is replaced in one pass, so a
        value that itself contains ``{{...}}`` is inserted as-is. Surrounding
        whitespace is ignored in both placeholder and argument names, so
        ``{{ name }}`` matches ``name``. A ``None`` value renders as an empty
        string, and a placeholder with no matching argument is left unchanged.

        Raises:
            PromptPlaceholdersDontMatchFormatArguments: If ``validate_placeholders``
                is True and the arguments don't match the placeholders.
        """
        if self._type == prompt_types.PromptType.MUSTACHE:
            template = self._template
            placeholders = extract_mustache_placeholder_keys(self._template)
            values = {key.strip(): value for key, value in kwargs.items()}
            kwargs_keys: Set[str] = set(values.keys())

            if kwargs_keys != placeholders and self._validate_placeholders:
                raise exceptions.PromptPlaceholdersDontMatchFormatArguments(
                    prompt_placeholders=placeholders, format_arguments=kwargs_keys
                )

            # A single pass, so a value that itself contains "{{...}}" is inserted
            # as-is instead of being substituted by a later key.
            def _replace(match: "re.Match[str]") -> str:
                key = match.group(1).strip()
                if key not in values:
                    return match.group(0)
                value = values[key]
                return "" if value is None else str(value)

            template = _MUSTACHE_PLACEHOLDER.sub(_replace, template)

        elif self._type == prompt_types.PromptType.JINJA2:
            template = jinja2.Template(self._template).render(**kwargs)
        else:
            template = self._template

        return template

    def __str__(self) -> str:
        return self._template


_MUSTACHE_PLACEHOLDER = re.compile(r"\{\{(.*?)\}\}")


def extract_mustache_placeholder_keys(prompt_template: str) -> Set[str]:
    """Return the ``{{key}}`` names in a mustache template, whitespace stripped."""
    return {key.strip() for key in _MUSTACHE_PLACEHOLDER.findall(prompt_template)}
