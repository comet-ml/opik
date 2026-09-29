import importlib.util
import sys
import types
import unittest
from pathlib import Path

# The script imports requests at module top, but these tests never touch the network.
sys.modules.setdefault("requests", types.ModuleType("requests"))

_SCRIPT_PATH = Path(__file__).resolve().parent.parent / "sync_provider_models.py"
_spec = importlib.util.spec_from_file_location("sync_provider_models", _SCRIPT_PATH)
spm = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(spm)


def _entry(value: str) -> spm.ModelEntry:
    return spm.ModelEntry(enum_name=value.upper(), value=value, structured_output=False, label=value)


def _reasoning_ids(yaml_text: str, section: str) -> set[str]:
    return set(spm._parse_yaml_reasoning_flags(yaml_text).get(section, {}))


class BuildReasoningLookupTest(unittest.TestCase):
    def test_reads_openai_text_models_and_skips_the_rest(self):
        prices = {
            "sample_spec": "not a model",
            "gpt-thinker": {"litellm_provider": "openai", "mode": "chat", "supports_reasoning": True},
            "gpt-plain": {"litellm_provider": "openai", "mode": "chat", "supports_reasoning": False},
            "gpt-unset": {"litellm_provider": "openai", "mode": "chat"},
            "gpt-thinker-pro": {"litellm_provider": "openai", "mode": "responses", "supports_reasoning": True},
            "gpt-image-x": {"litellm_provider": "openai", "mode": "image_generation", "supports_reasoning": True},
            "claude-thinker": {"litellm_provider": "anthropic", "mode": "chat", "supports_reasoning": True},
            "azure/gpt-thinker": {"litellm_provider": "azure", "mode": "chat", "supports_reasoning": True},
        }

        lookup = spm._build_reasoning_lookup(prices)

        self.assertEqual(
            lookup,
            {"gpt-thinker": True, "gpt-plain": False, "gpt-unset": False, "gpt-thinker-pro": True},
        )


class RegenerateYamlReasoningTest(unittest.TestCase):
    EXISTING_YAML = (
        "openai:\n"
        '  - id: "gpt-carried"\n'
        "    reasoning: true\n"
        "anthropic:\n"
        '  - id: "claude-carried"\n'
        "    reasoning: true\n"
    )

    CHAT_LATEST = sorted(spm.OPENAI_NON_REASONING_MODELS)[0]

    def _regenerate(self, openai_reasoning):
        models = {
            "openai": [_entry("gpt-carried"), _entry("gpt-new"), _entry("gpt-plain"), _entry(self.CHAT_LATEST)],
            "anthropic": [_entry("claude-carried"), _entry("claude-new")],
        }
        return spm.regenerate_llm_models_yaml(self.EXISTING_YAML, models, openai_reasoning=openai_reasoning)

    def test_openai_section_is_seeded_from_lookup(self):
        yaml_text = self._regenerate({"gpt-new": True, "gpt-plain": False})

        self.assertIn("gpt-new", _reasoning_ids(yaml_text, "openai"))
        self.assertNotIn("gpt-plain", _reasoning_ids(yaml_text, "openai"))

    def test_existing_flag_is_kept_when_lookup_lacks_it(self):
        yaml_text = self._regenerate({"gpt-new": True})

        self.assertIn("gpt-carried", _reasoning_ids(yaml_text, "openai"))

    def test_pinned_non_reasoning_model_is_never_flagged(self):
        yaml_text = self._regenerate({self.CHAT_LATEST: True})

        self.assertNotIn(self.CHAT_LATEST, _reasoning_ids(yaml_text, "openai"))

    def test_other_sections_are_not_seeded_from_lookup(self):
        yaml_text = self._regenerate({"claude-new": True, "gpt-new": True})

        self.assertEqual(_reasoning_ids(yaml_text, "anthropic"), {"claude-carried"})

    def test_without_lookup_only_carried_flags_remain(self):
        yaml_text = self._regenerate(None)

        self.assertEqual(_reasoning_ids(yaml_text, "openai"), {"gpt-carried"})


if __name__ == "__main__":
    unittest.main()
