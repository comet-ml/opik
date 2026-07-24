import os

from . import validator


def construct_prompt_injection_validator() -> validator.PromptInjectionValidator:
    # No defaults: the base model, adapter repo, and token are all provided by
    # the deployment. An unset (or empty) value becomes None so the validator
    # can raise a clear error listing what is missing on first use.
    base_model = os.getenv("OPIK_GUARDRAILS_PROMPT_INJECTION_BASE_MODEL") or None
    adapter_repo = os.getenv("OPIK_GUARDRAILS_PROMPT_INJECTION_MODEL") or None
    token = os.getenv("HF_TOKEN") or None
    return validator.PromptInjectionValidator(base_model, adapter_repo, token)
