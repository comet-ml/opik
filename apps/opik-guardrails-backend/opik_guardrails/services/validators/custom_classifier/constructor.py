import os

from . import validator

DEFAULT_ADAPTERS_DIR = "/adapters"


def construct_custom_classifier_validator() -> validator.CustomClassifierValidator:
    adapters_dir = os.getenv("OPIK_GUARDRAILS_ADAPTERS_DIR") or DEFAULT_ADAPTERS_DIR
    return validator.CustomClassifierValidator(adapters_dir)
