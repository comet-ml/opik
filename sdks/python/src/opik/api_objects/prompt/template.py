from typing import Dict, Any, Set
from opik import exceptions

import re
import functools

def format(template: str, arguments: Dict[str, Any]):

    argument_keys = set(arguments.keys())
    prompt_placeholders = 

    if argument_keys != self._prompt_placeholders:
        raise exceptions.PromptPlaceholdersDontMatchFormatArguments(prompt_placeholders=self._prompt_placeholders, format_arguments=argument_keys)

    for key, value in kwargs.items():
        template = template.replace(f"{{{{{key}}}}}", str(value))
    return template


@functools.lru_cache
def _extract_prompt_variable_keys(prompt_template: str) -> Set[str]:
    pattern = r"\{\{(.*?)\}\}"
    return set(re.findall(pattern, prompt_template))
