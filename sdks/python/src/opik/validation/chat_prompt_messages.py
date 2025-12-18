from typing import Any, List

import opik.exceptions as exceptions
from . import validator, result


class ChatPromptMessagesValidator(validator.Validator):
    """
    Validator for ChatPrompt messages list.
    
    Validates that messages is a list of dicts with:
    - "role" key with value "system", "user", or "assistant"
    - "content" key with value either string or list of dicts
    - If content is list of dicts, each dict must have "type" key
    """

    VALID_ROLES = {"system", "user", "assistant"}

    def __init__(self, messages: Any):
        self.messages = messages
        self.validation_result: result.ValidationResult | None = None

    def validate(self) -> result.ValidationResult:
        failure_reasons: List[str] = []

        # Validate messages is a list
        if not isinstance(self.messages, list):
            msg = (
                f"messages must be a list but {type(self.messages).__name__} was given"
            )
            failure_reasons.append(msg)
            self.validation_result = result.ValidationResult(
                failed=True, failure_reasons=failure_reasons
            )
            return self.validation_result

        # Validate each message in the list
        for idx, message in enumerate(self.messages):
            prefix = f"messages[{idx}]"

            # Validate message is a dict
            if not isinstance(message, dict):
                msg = (
                    f"{prefix}: must be a dict but {type(message).__name__} was given"
                )
                failure_reasons.append(msg)
                continue

            # Validate message has exactly "role" and "content" keys
            message_keys = set(message.keys())
            expected_keys = {"role", "content"}

            if message_keys != expected_keys:
                if not message_keys.issubset(expected_keys):
                    missing_keys = expected_keys - message_keys
                    msg = (
                        f"{prefix}: missing required keys: {sorted(missing_keys)}"
                    )
                    failure_reasons.append(msg)
                if not expected_keys.issubset(message_keys):
                    extra_keys = message_keys - expected_keys
                    msg = (
                        f"{prefix}: unexpected keys: {sorted(extra_keys)}. "
                        f"Expected only: {sorted(expected_keys)}"
                    )
                    failure_reasons.append(msg)
                continue

            # Validate role
            role = message.get("role")
            if role not in self.VALID_ROLES:
                valid_roles_str = ", ".join([f"'{r}'" for r in sorted(self.VALID_ROLES)])
                msg = (
                    f"{prefix}.role: must be one of [{valid_roles_str}] "
                    f"but {repr(role)} was given"
                )
                failure_reasons.append(msg)

            # Validate content
            content = message.get("content")
            if content is None:
                msg = f"{prefix}.content: must not be None"
                failure_reasons.append(msg)
            elif not isinstance(content, (str, list)):
                msg = (
                    f"{prefix}.content: must be either str or list of dicts "
                    f"but {type(content).__name__} was given"
                )
                failure_reasons.append(msg)
            elif isinstance(content, list):
                # Validate content is list of dicts with "type" key
                for content_idx, content_part in enumerate(content):
                    content_prefix = f"{prefix}.content[{content_idx}]"
                    if not isinstance(content_part, dict):
                        msg = (
                            f"{content_prefix}: must be a dict "
                            f"but {type(content_part).__name__} was given"
                        )
                        failure_reasons.append(msg)
                    elif "type" not in content_part:
                        msg = f"{content_prefix}: must have 'type' key"
                        failure_reasons.append(msg)

        # Create validation result
        if len(failure_reasons) > 0:
            self.validation_result = result.ValidationResult(
                failed=True, failure_reasons=failure_reasons
            )
        else:
            self.validation_result = result.ValidationResult(failed=False)

        return self.validation_result

    def raise_validation_error(self) -> None:
        if (
            self.validation_result is not None
            and len(self.validation_result.failure_reasons) > 0
        ):
            raise exceptions.ValidationError(
                prefix="ChatPrompt.__init__",
                failure_reasons=self.validation_result.failure_reasons,
            )

