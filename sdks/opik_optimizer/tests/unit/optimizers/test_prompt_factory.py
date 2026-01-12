from __future__ import annotations

from opik_optimizer.algorithms.evolutionary_optimizer import EvolutionaryOptimizer
from opik_optimizer.algorithms.few_shot_bayesian_optimizer import (
    FewShotBayesianOptimizer,
)
from opik_optimizer.algorithms.hierarchical_reflective_optimizer import (
    HierarchicalReflectiveOptimizer,
)
from opik_optimizer.algorithms.meta_prompt_optimizer import MetaPromptOptimizer
from opik_optimizer.utils.prompt_library import PromptLibrary


def test_few_shot_prompt_factory_updates_placeholder() -> None:
    def factory(prompts: PromptLibrary) -> None:
        prompts.set("example_placeholder", "FEW_SHOT_EXAMPLES")

    optimizer = FewShotBayesianOptimizer(prompt_overrides=factory)

    assert optimizer.prompts.get("example_placeholder") == "FEW_SHOT_EXAMPLES"


def test_meta_prompt_factory_updates_template() -> None:
    def factory(prompts: PromptLibrary) -> None:
        prompts.set("candidate_generation", "CANDIDATE {best_score}")

    optimizer = MetaPromptOptimizer(prompt_overrides=factory)

    assert optimizer.prompts.get("candidate_generation") == "CANDIDATE {best_score}"
    assert optimizer.hall_of_fame is not None


def test_hierarchical_prompt_factory_updates_analyzer() -> None:
    def factory(prompts: PromptLibrary) -> None:
        prompts.set("batch_analysis_prompt", "CUSTOM {formatted_batch}")

    optimizer = HierarchicalReflectiveOptimizer(prompt_overrides=factory)

    assert optimizer.prompts.get("batch_analysis_prompt") == "CUSTOM {formatted_batch}"
    assert optimizer._hierarchical_analyzer.prompts is optimizer.prompts


def test_evolutionary_prompt_factory_updates_templates() -> None:
    def factory(prompts: PromptLibrary) -> None:
        prompts.set("synonyms_system_prompt", "Return one synonym word.")

    optimizer = EvolutionaryOptimizer(prompt_overrides=factory)

    assert optimizer.prompts.get("synonyms_system_prompt") == "Return one synonym word."
