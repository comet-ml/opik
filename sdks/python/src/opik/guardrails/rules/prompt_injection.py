from typing import List, Dict, Any

from . import rule
from .. import schemas


class PromptInjection(rule.Rule):
    """
    Rule that detects attempts to manipulate the assistant, such as prompt injection and
    jailbreaks.

    Checked by the Opik backend.
    """

    def get_validation_configs(self) -> List[Dict[str, Any]]:
        return [{"type": schemas.ValidationType.PROMPT_INJECTION, "config": {}}]
