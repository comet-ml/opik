"""Text normalization helpers for LLM outputs."""

from __future__ import annotations


def normalize_llm_text(text: str) -> str:
    """Normalize LLM responses for parsing and fingerprinting."""
    if not isinstance(text, str):
        return str(text)
    # Normalize newlines and whitespace.
    normalized = text.replace("\r\n", "\n").replace("\r", "\n").strip()
    # Collapse excessive blank lines and spaces.
    normalized = "\n".join(line.rstrip() for line in normalized.splitlines())
    # Remove fenced JSON blocks if present.
    if normalized.startswith("```"):
        normalized = normalized.strip("`")
        if normalized.lower().startswith("json"):
            normalized = normalized[4:]
    return normalized.strip()
