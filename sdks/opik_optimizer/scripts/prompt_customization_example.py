"""Prompt customization examples for Opik Optimizer.

Use this script as a quick reference for the prompt library surface:
- Each optimizer exposes DEFAULT_PROMPTS (string templates used internally).
- prompt_overrides lets you replace or transform those templates without
  modifying optimizer source code.
- optimizer.get_prompt returns the current template after overrides.

Typical use-cases:
- Tighten output style constraints (formatting, brevity, tone).
- Add domain-specific constraints (legal, medical, coding standards).
- Inject extra safety or compliance requirements into reasoning prompts.
"""

from __future__ import annotations

from opik_optimizer import EvolutionaryOptimizer, MetaPromptOptimizer
from opik_optimizer.utils.prompt_library import PromptLibrary


def _preview(text: str, limit: int = 120) -> str:
    """Return a single-line preview for console output."""
    flat = " ".join(text.strip().split())
    return flat[:limit] + ("..." if len(flat) > limit else "")


def list_prompt_keys() -> None:
    """Print available prompt keys for a default optimizer instance.

    Use this to discover which templates exist before overriding any of them.
    Keys differ per optimizer, so listing them helps avoid KeyError from typos.
    """
    optimizer = EvolutionaryOptimizer(model="openai/gpt-5-mini")
    print("EvolutionaryOptimizer prompt keys:")
    for key in optimizer.list_prompts():
        print(f"- {key}")


def dict_overrides_example() -> None:
    """Show a dict-based override that replaces a single prompt template.

    Dict overrides are best when you already know which template you want to
    replace and you want a static replacement string.
    """
    custom_synonyms_prompt = (
        "Given a word, return ONE synonym with the same meaning. "
        "Return only the word."
    )
    optimizer = EvolutionaryOptimizer(
        model="openai/gpt-5-mini",
        prompt_overrides={"synonyms_system_prompt": custom_synonyms_prompt},
    )
    print("Custom synonyms prompt preview:")
    print(f"- {_preview(optimizer.get_prompt('synonyms_system_prompt'))}")


def callable_overrides_example() -> None:
    """Show a callable override that edits a prompt template in place.

    Callable overrides are best when you need conditional logic, want to reuse
    the existing template, or need to update multiple templates at once.
    """
    def add_prefix(prompts: PromptLibrary) -> None:
        """Prepend a short instruction to the reasoning template.

        This pattern is useful for adding consistent guardrails or style
        requirements across all prompt generations without rewriting the full
        template.
        """
        key = "reasoning_system"
        if key in prompts.keys():
            prompts.set(key, "Always respond in English.\n\n" + prompts.get(key))

    optimizer = MetaPromptOptimizer(
        model="openai/gpt-5-mini",
        prompt_overrides=add_prefix,
    )
    print("Custom reasoning prompt preview:")
    print(f"- {_preview(optimizer.get_prompt('reasoning_system'))}")


if __name__ == "__main__":
    print("Opik Optimizer prompt customization quickstart")
    list_prompt_keys()
    dict_overrides_example()
    callable_overrides_example()
