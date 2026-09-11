from typing import Any, List, Optional

import opik.exceptions as exceptions
from . import validator, result


class ChatPromptMessagesValidator(validator.RaisableValidator):
    """
    Validator for ChatPrompt messages list.

    Validates that messages is a list of dicts with:
    - "role" key with value "system", "user", or "assistant"
    - "content" key with value either string or list of dicts
    - If content is list of dicts, each dict must have "type" key
    """

    VALID_ROLES = {"system", "user", "assistant"}
    URL_BASED_CONTENT_TYPES = {"image_url", "video_url", "audio_url"}

    def __init__(self, messages: Any):
        self.messages = messages
        self.validation_result: Optional[result.ValidationResult] = None

    def validate(self) -> result.ValidationResult:
        failure_reasons: List[str] = []

        # Validate messages is a list
        if not self._validate_messages_is_list(failure_reasons):
            self.validation_result = result.ValidationResult(
                failed=True, failure_reasons=failure_reasons
            )
            return self.validation_result

        # Validate each message in the list
        for idx, message in enumerate(self.messages):
            prefix = f"messages[{idx}]"
            self._validate_message(prefix, message, failure_reasons)

        # Create validation result
        if len(failure_reasons) > 0:
            self.validation_result = result.ValidationResult(
                failed=True, failure_reasons=failure_reasons
            )
        else:
            self.validation_result = result.ValidationResult(failed=False)

        return self.validation_result

    def _validate_messages_is_list(self, failure_reasons: List[str]) -> bool:
        """Validate that messages is a list. Returns False if validation fails."""
        if not isinstance(self.messages, list):
            msg = (
                f"messages must be a list but {type(self.messages).__name__} was given"
            )
            failure_reasons.append(msg)
            return False
        return True

    def _validate_message(
        self, prefix: str, message: Any, failure_reasons: List[str]
    ) -> None:
        """Validate a single message structure, role, and content."""
        if not self._validate_message_structure(prefix, message, failure_reasons):
            return

        self._validate_role(prefix, message, failure_reasons)
        self._validate_content(prefix, message, failure_reasons)

    def _validate_message_structure(
        self, prefix: str, message: Any, failure_reasons: List[str]
    ) -> bool:
        """Validate that message is a dict with exactly 'role' and 'content' keys. Returns False if validation fails."""
        # Validate message is a dict
        if not isinstance(message, dict):
            msg = f"{prefix}: must be a dict but {type(message).__name__} was given"
            failure_reasons.append(msg)
            return False

        # Validate message has exactly "role" and "content" keys
        message_keys = set(message.keys())
        expected_keys = {"role", "content"}

        if message_keys != expected_keys:
            if not message_keys.issubset(expected_keys):
                missing_keys = expected_keys - message_keys
                msg = f"{prefix}: missing required keys: {sorted(missing_keys)}"
                failure_reasons.append(msg)
            if not expected_keys.issubset(message_keys):
                extra_keys = message_keys - expected_keys
                msg = (
                    f"{prefix}: unexpected keys: {sorted(extra_keys)}. "
                    f"Expected only: {sorted(expected_keys)}"
                )
                failure_reasons.append(msg)
            return False

        return True

    def _validate_role(
        self, prefix: str, message: dict, failure_reasons: List[str]
    ) -> None:
        """Validate the role field of a message."""
        role = message.get("role")
        if role not in self.VALID_ROLES:
            valid_roles_str = ", ".join([f"'{r}'" for r in sorted(self.VALID_ROLES)])
            msg = (
                f"{prefix}.role: must be one of [{valid_roles_str}] "
                f"but {repr(role)} was given"
            )
            failure_reasons.append(msg)

    def _validate_content(
        self, prefix: str, message: dict, failure_reasons: List[str]
    ) -> None:
        """Validate the content field of a message."""
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
            self._validate_content_list(prefix, content, failure_reasons)

    def _validate_content_list(
        self, prefix: str, content: list, failure_reasons: List[str]
    ) -> None:
        """Validate content when it is a list of content parts."""
        for content_idx, content_part in enumerate(content):
            content_prefix = f"{prefix}.content[{content_idx}]"
            self._validate_content_part(content_prefix, content_part, failure_reasons)

    def _validate_content_part(
        self, content_prefix: str, content_part: Any, failure_reasons: List[str]
    ) -> None:
        """Validate a single content part in the content list."""
        if not isinstance(content_part, dict):
            msg = (
                f"{content_prefix}: must be a dict "
                f"but {type(content_part).__name__} was given"
            )
            failure_reasons.append(msg)
            return

        if "type" not in content_part:
            msg = f"{content_prefix}: must have 'type' key"
            failure_reasons.append(msg)
            return

        # Validate type-specific requirements
        content_type = content_part.get("type")
        self._validate_content_type_specific(
            content_prefix, content_type, content_part, failure_reasons
        )

    def _validate_content_type_specific(
        self,
        content_prefix: str,
        content_type: Any,
        content_part: dict,
        failure_reasons: List[str],
    ) -> None:
        """Validate type-specific requirements for content parts."""
        if content_type in self.URL_BASED_CONTENT_TYPES:
            self._validate_required_url_object(
                content_prefix,
                content_part,
                content_type,
                content_type,
                failure_reasons,
            )
        elif content_type == "text":
            self._validate_required_string_key(
                content_prefix, content_part, "text", "text", failure_reasons
            )

    def _validate_required_string_key(
        self,
        prefix: str,
        content_part: dict,
        key_name: str,
        type_name: str,
        failure_reasons: List[str],
    ) -> None:
        """Validate that a required key exists and is a string."""
        if key_name not in content_part:
            msg = f"{prefix}: must have '{key_name}' key when type is '{type_name}'"
            failure_reasons.append(msg)
        elif not isinstance(content_part.get(key_name), str):
            msg = (
                f"{prefix}.{key_name}: must be a string "
                f"but {type(content_part.get(key_name)).__name__} was given"
            )
            failure_reasons.append(msg)

    def _validate_required_url_object(
        self,
        prefix: str,
        content_part: dict,
        key_name: str,
        type_name: str,
        failure_reasons: List[str],
    ) -> None:
        """Validate that a required key exists and is a dict with a 'url' key that is a string."""
        if key_name not in content_part:
            msg = f"{prefix}: must have '{key_name}' key when type is '{type_name}'"
            failure_reasons.append(msg)
            return

        url_object = content_part.get(key_name)
        if not isinstance(url_object, dict):
            msg = (
                f"{prefix}.{key_name}: must be a dict "
                f"but {type(url_object).__name__} was given"
            )
            failure_reasons.append(msg)
            return

        if "url" not in url_object:
            msg = f"{prefix}.{key_name}: must have 'url' key"
            failure_reasons.append(msg)
        elif not isinstance(url_object.get("url"), str):
            msg = (
                f"{prefix}.{key_name}.url: must be a string "
                f"but {type(url_object.get('url')).__name__} was given"
            )
            failure_reasons.append(msg)

    def raise_if_validation_failed(self) -> None:
        if (
            self.validation_result is not None
            and len(self.validation_result.failure_reasons) > 0
        ):
            raise exceptions.ValidationError(
                prefix="ChatPrompt.__init__",
                failure_reasons=self.validation_result.failure_reasons,
            )
