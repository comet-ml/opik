"""Load ARC-AGI system/user prompts from disk."""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path


def _read_file(name: str) -> str:
    base = Path(__file__).resolve().parents[1] / "prompts"
    return (base / name).read_text(encoding="utf-8").strip()


@lru_cache(maxsize=1)
def load_prompts() -> tuple[str, str]:
    """
    Return the cached (system_prompt, user_prompt) tuple.

    The prompt files are static during a run, so memoizing the read avoids
    hitting the filesystem every time ``tasks_optimizer`` is imported.
    """
    system_prompt = _read_file("system_prompt.md")
    user_prompt = _read_file("user_prompt.md")
    return system_prompt, user_prompt


__all__ = ["load_prompts"]
