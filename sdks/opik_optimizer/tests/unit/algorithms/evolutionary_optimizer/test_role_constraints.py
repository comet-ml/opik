from __future__ import annotations

from opik_optimizer.algorithms.evolutionary_optimizer.ops import mutation_ops
from opik_optimizer.utils.prompt_library import PromptLibrary


def test_deap_mutation_returns_same_when_no_roles_allowed() -> None:
    individual = {"p": [{"role": "user", "content": "{question}"}]}
    mutated = mutation_ops.deap_mutation(
        individual=individual,
        optimizer=None,
        current_population=None,
        output_style_guidance="",
        initial_prompts={},
        model="gpt-4o",
        model_parameters={},
        diversity_threshold=0.0,
        optimization_id=None,
        verbose=0,
        prompts=PromptLibrary({}),  # not used when allowed_roles is empty
        allowed_roles=set(),
    )
    assert mutated is individual
