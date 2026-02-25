from __future__ import annotations


import pytest

import opik_optimizer
from opik import Dataset
from .utils import MultiPromptTestAgent


@pytest.fixture(scope="session")
def tiny_dataset() -> Dataset:
    """Shared tiny dataset for e2e optimizer tests."""
    try:
        return opik_optimizer.datasets.tiny_test()
    except Exception as exc:  # pragma: no cover
        # This dataset helper may download assets (e.g., from HuggingFace). In offline
        # environments (CI sandboxes, airplane mode), fail gracefully by skipping.
        pytest.skip(f"tiny_test dataset unavailable (likely offline): {exc}")


@pytest.fixture
def multi_prompt_agent() -> MultiPromptTestAgent:
    """Shared agent for running multi-prompt optimizer e2e tests."""
    return MultiPromptTestAgent(
        model="openai/gpt-5-nano",
        model_parameters={"temperature": 0.7},
    )
