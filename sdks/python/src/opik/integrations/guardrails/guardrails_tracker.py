from typing import Optional

import guardrails
from . import guardrails_patcher


def track_guardrails(
    guard: guardrails.Guard, project_name: Optional[str] = None
) -> guardrails.Guard:
    patcher = guardrails_patcher.GuardValidatorsPatcher(project_name)
    patcher.patch()

    return guard
