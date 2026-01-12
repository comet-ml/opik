"""Tests for PromptLibrary."""

import pytest

from opik_optimizer.utils.prompt_library import PromptLibrary


class TestPromptLibrary:
    """Tests for PromptLibrary class."""

    def test_basic_initialization(self) -> None:
        """Should initialize with defaults."""
        defaults = {"prompt1": "template1", "prompt2": "template2"}
        lib = PromptLibrary(defaults)

        assert lib.get("prompt1") == "template1"
        assert lib.get("prompt2") == "template2"

    def test_get_with_formatting(self) -> None:
        """Should format templates with kwargs."""
        defaults = {"greeting": "Hello {name}!"}
        lib = PromptLibrary(defaults)

        result = lib.get("greeting", name="World")
        assert result == "Hello World!"

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

    def test_set_method(self) -> None:
        """Should set individual prompts."""
        defaults = {"prompt1": "original"}
        lib = PromptLibrary(defaults)

        lib.set("prompt1", "updated")
        assert lib.get("prompt1") == "updated"

    def test_update_method(self) -> None:
        """Should update multiple prompts at once."""
        defaults = {"prompt1": "original", "prompt2": "original2"}
        lib = PromptLibrary(defaults)

        lib.update({"prompt1": "new1", "prompt2": "new2"})
        assert lib.get("prompt1") == "new1"
        assert lib.get("prompt2") == "new2"

    def test_keys_method(self) -> None:
        """Should list available keys."""
        defaults = {"prompt1": "t1", "prompt2": "t2"}
        lib = PromptLibrary(defaults)

        keys = lib.keys()
        assert sorted(keys) == ["prompt1", "prompt2"]

    def test_get_default(self) -> None:
        """Should retrieve original defaults after overrides."""
        defaults = {"prompt1": "original"}
        lib = PromptLibrary(defaults, overrides={"prompt1": "overridden"})

        assert lib.get("prompt1") == "overridden"
        assert lib.get_default("prompt1") == "original"

    # Error cases

    def test_dict_override_unknown_key_raises_error(self) -> None:
        """Should raise ValueError for unknown keys in dict overrides."""
        defaults = {"prompt1": "template"}

        with pytest.raises(ValueError, match="Unknown prompt keys: \\['unknown'\\]"):
            PromptLibrary(defaults, overrides={"unknown": "value"})

    def test_invalid_override_type_raises_error(self) -> None:
        """Should raise TypeError for invalid override types."""
        defaults = {"prompt1": "template"}

        with pytest.raises(TypeError, match="prompt_overrides must be dict or callable"):
            PromptLibrary(defaults, overrides="invalid")  # type: ignore

    def test_get_unknown_key_raises_error(self) -> None:
        """Should raise KeyError for unknown key in get()."""
        defaults = {"prompt1": "template"}
        lib = PromptLibrary(defaults)

        with pytest.raises(KeyError, match="Unknown prompt 'unknown'"):
            lib.get("unknown")

    def test_set_unknown_key_raises_error(self) -> None:
        """Should raise KeyError for unknown key in set()."""
        defaults = {"prompt1": "template"}
        lib = PromptLibrary(defaults)

        with pytest.raises(KeyError, match="Unknown prompt 'unknown'"):
            lib.set("unknown", "value")

    def test_update_unknown_key_raises_error(self) -> None:
        """Should raise KeyError for unknown keys in update()."""
        defaults = {"prompt1": "template"}
        lib = PromptLibrary(defaults)

        with pytest.raises(KeyError, match="Unknown keys: \\['unknown'\\]"):
            lib.update({"unknown": "value"})

    def test_get_default_unknown_key_raises_error(self) -> None:
        """Should raise KeyError for unknown key in get_default()."""
        defaults = {"prompt1": "template"}
        lib = PromptLibrary(defaults)

        with pytest.raises(KeyError, match="Unknown prompt 'unknown'"):
            lib.get_default("unknown")
