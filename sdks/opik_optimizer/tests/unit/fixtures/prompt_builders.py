"""
Shared prompt builders for optimizer tests.
"""

from __future__ import annotations

from opik_optimizer import ChatPrompt


def make_baseline_prompt() -> ChatPrompt:
    return ChatPrompt(system="baseline", user="{question}")


def make_two_prompt_bundle() -> dict[str, ChatPrompt]:
    return {
        "main": ChatPrompt(name="main", system="Main", user="{question}"),
        "secondary": ChatPrompt(name="secondary", system="Secondary", user="{input}"),
    }
