"""Prompt library for optimizer customization.

This module provides the PromptLibrary class for managing prompt templates
with support for overrides via dict or callable.

Example usage:

    # Simple dict override
    optimizer = MetaPromptOptimizer(
        model="gpt-4o",
        prompt_overrides={"reasoning_system": "Custom prompt..."}
    )

    # Callable override with full access
    def customize(prompts: PromptLibrary) -> None:
        print(prompts.keys())  # List available keys
        original = prompts.get("reasoning_system")
        prompts.set("reasoning_system", original + "\\nBe concise.")

    optimizer = MetaPromptOptimizer(model="gpt-4o", prompt_overrides=customize)
"""

from collections.abc import Callable

__all__ = ["PromptLibrary", "PromptOverrides"]


class PromptLibrary:
    """Manages prompts with override support.

    This class handles:
    - Storage of default and overridden prompts
    - Validation of prompt keys
    - Formatting of prompt templates with kwargs
    - Both dict and callable override patterns

    Args:
        defaults: Dictionary of default prompt templates
        overrides: Optional dict or callable to customize prompts
    """

    def __init__(
        self,
        defaults: dict[str, str],
        overrides: "PromptOverrides" = None,
    ):
        self._defaults = dict(defaults)
        self._prompts = dict(defaults)
        self._valid_keys = set(defaults.keys())

        if overrides is not None:
            self._apply_overrides(overrides)

    def _apply_overrides(self, overrides: "PromptOverrides") -> None:
        """Apply dict or callable overrides."""
        if isinstance(overrides, dict):
            unknown = set(overrides) - self._valid_keys
            if unknown:
                raise ValueError(
                    f"Unknown prompt keys: {sorted(unknown)}. "
                    f"Available: {sorted(self._valid_keys)}"
                )
            self._prompts.update(overrides)
        elif callable(overrides):
            overrides(self)  # Pass self for modification
        else:
            raise TypeError(
                f"prompt_overrides must be dict or callable, got {type(overrides)}"
            )

    def keys(self) -> list[str]:
        """List available prompt keys."""
        return sorted(self._valid_keys)

    def set(self, key: str, value: str) -> None:
        """Set a prompt by key.

        Args:
            key: The prompt key to set
            value: The new prompt template

        Raises:
            KeyError: If key is not a valid prompt key
        """
        if key not in self._valid_keys:
            raise KeyError(f"Unknown prompt '{key}'. Available: {self.keys()}")
        self._prompts[key] = value

    def update(self, overrides: dict[str, str]) -> None:
        """Update multiple prompts.

        Args:
            overrides: Dictionary of key-value pairs to update

        Raises:
            KeyError: If any key is not a valid prompt key
        """
        unknown = set(overrides) - self._valid_keys
        if unknown:
            raise KeyError(f"Unknown keys: {sorted(unknown)}. Available: {self.keys()}")
        self._prompts.update(overrides)

    # === Read methods (for optimization) ===

    def get(self, key: str, **fmt: object) -> str:
        """Get a prompt, optionally formatted with kwargs.

        Args:
            key: The prompt key to retrieve
            **fmt: Optional format kwargs to apply to the template

        Returns:
            The prompt template, formatted if kwargs provided

        Raises:
            KeyError: If key is not a valid prompt key
        """
        if key not in self._valid_keys:
            raise KeyError(f"Unknown prompt '{key}'. Available: {self.keys()}")
        template = self._prompts[key]
        return template.format(**fmt) if fmt else template

    def get_default(self, key: str) -> str:
        """Get original default prompt (before overrides).

        Args:
            key: The prompt key to retrieve

        Returns:
            The original default template

        Raises:
            KeyError: If key is not a valid prompt key
        """
        if key not in self._defaults:
            raise KeyError(f"Unknown prompt '{key}'")
        return self._defaults[key]


PromptOverrides = dict[str, str] | Callable[[PromptLibrary], None] | None
