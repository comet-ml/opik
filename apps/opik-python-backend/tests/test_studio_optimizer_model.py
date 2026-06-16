"""Unit tests for the optional separate optimizer/algorithm model.

The Optimization Studio lets the optimizer/algorithm (GEPA's reflection LM,
hierarchical's reasoning model) run on a different model than the prompt. That
model is carried inside ``studio_config.optimizer.parameters`` and must be
pulled out of the parameters so the rest can be passed as kwargs to the
optimizer constructor without colliding with ``model``.
"""

from opik_backend.studio.types import OptimizationConfig


def _config(optimizer_params: dict) -> dict:
    return {
        "dataset_name": "ds",
        "prompt": {"messages": [{"role": "user", "content": "{{text}}"}]},
        "llm_model": {
            "model": "claude-haiku-4-5-20251001",
            "parameters": {"stream": False},
        },
        "evaluation": {
            "metrics": [{"type": "equals", "parameters": {"reference_key": "label"}}]
        },
        "optimizer": {"type": "gepa", "parameters": optimizer_params},
    }


def test_optimizer_model_extracted_from_optimizer_params():
    config = OptimizationConfig.from_dict(
        _config(
            {
                "seed": 42,
                "model": "claude-opus-4-8",
                "model_parameters": {"temperature": 0.5},
            }
        )
    )

    # The separate algorithm model + its params are surfaced...
    assert config.optimizer_model == "claude-opus-4-8"
    assert config.optimizer_model_params == {"temperature": 0.5}
    # ...and removed from the kwargs passed to the optimizer constructor.
    assert config.optimizer_params == {"seed": 42}
    # The prompt/task model is untouched.
    assert config.model == "claude-haiku-4-5-20251001"


def test_optimizer_model_defaults_to_none_when_absent():
    config = OptimizationConfig.from_dict(_config({"seed": 7}))

    assert config.optimizer_model is None
    assert config.optimizer_model_params is None
    assert config.optimizer_params == {"seed": 7}
