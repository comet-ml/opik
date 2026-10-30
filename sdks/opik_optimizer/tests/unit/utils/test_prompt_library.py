"""Tests for PromptLibrary."""

import pytest

from opik_optimizer.utils.prompt_library import PromptLibrary


class TestPromptLibrary:
    """Tests for PromptLibrary class."""

    def test_dict_overrides(self) -> None:
        """Should apply dict overrides."""
        defaults = {"prompt1": "original", "prompt2": "original2"}
        lib = PromptLibrary(defaults, overrides={"prompt1": "overridden"})

        assert lib.get("prompt1") == "overridden"
        assert lib.get("prompt2") == "original2"

    def test_callable_overrides(self) -> None:
        """Should apply callable overrides (factory pattern)."""
        defaults = {"prompt1": "original", "prompt2": "original2"}

        def factory(prompts: PromptLibrary) -> None:
            prompts.set("prompt1", "custom")

        lib = PromptLibrary(defaults, overrides=factory)

        assert lib.get("prompt1") == "custom"
        assert lib.get("prompt2") == "original2"

    # Error cases

    def test_dict_override_unknown_key_raises_error(self) -> None:
        """Should raise ValueError for unknown keys in dict overrides."""
        defaults = {"prompt1": "template"}

        with pytest.raises(ValueError, match="Unknown prompt keys: \\['unknown'\\]"):
            PromptLibrary(defaults, overrides={"unknown": "value"})
