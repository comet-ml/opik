"""Pytest fixtures for PromptLibrary-based defaults."""

from __future__ import annotations

import pytest


@pytest.fixture
def evo_prompts():
    """PromptLibrary for evolutionary optimizer tests."""
    from opik_optimizer.algorithms.evolutionary_optimizer import EvolutionaryOptimizer
    from opik_optimizer.utils.prompt_library import PromptLibrary

    return PromptLibrary(EvolutionaryOptimizer.DEFAULT_PROMPTS)
