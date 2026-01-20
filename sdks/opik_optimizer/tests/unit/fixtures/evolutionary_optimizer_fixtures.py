"""Pytest plugin fixtures for EvolutionaryOptimizer unit tests.

These fixtures make the evolutionary optimizer tests deterministic and fast by:
- avoiding real LLM-based crossover/mutations
- short-circuiting the inner optimization loop while preserving accounting semantics
"""

from __future__ import annotations

from typing import Any

import pytest

from opik_optimizer import EvolutionaryOptimizer
from opik_optimizer.api_objects import chat_prompt
from opik_optimizer.algorithms.evolutionary_optimizer.ops import crossover_ops, mutation_ops
from opik_optimizer.core.state import AlgorithmResult


def _should_apply_evolutionary_optimizer_shortcuts(request: pytest.FixtureRequest) -> bool:
    # Only apply to EvolutionaryOptimizer *algorithm* tests, not the underlying ops
    # tests (which need real llm/semantic behavior to be testable).
    return "test_evolutionary_optimizer" in request.node.nodeid


@pytest.fixture(autouse=True)
def _disable_llm_crossover(
    monkeypatch: pytest.MonkeyPatch, request: pytest.FixtureRequest
) -> None:
    """Force deterministic DEAP crossover to avoid real LLM calls in unit tests."""
    if not _should_apply_evolutionary_optimizer_shortcuts(request):
        return
    monkeypatch.setattr(
        crossover_ops,
        "llm_deap_crossover",
        lambda ind1, ind2, **kwargs: crossover_ops.deap_crossover(
            ind1, ind2, verbose=kwargs.get("verbose", 1)
        ),
    )


@pytest.fixture(autouse=True)
def _disable_llm_mutations(
    monkeypatch: pytest.MonkeyPatch, request: pytest.FixtureRequest
) -> None:
    """Avoid semantic/structural LLM mutations in unit tests."""
    if not _should_apply_evolutionary_optimizer_shortcuts(request):
        return
    monkeypatch.setattr(
        mutation_ops, "_semantic_mutation", lambda **kwargs: kwargs["prompt"]
    )
    monkeypatch.setattr(
        mutation_ops, "_structural_mutation", lambda **kwargs: kwargs["prompt"]
    )
    monkeypatch.setattr(
        mutation_ops, "_word_level_mutation_prompt", lambda **kwargs: kwargs["prompt"]
    )


@pytest.fixture(autouse=True)
def _minimize_generation_work(
    monkeypatch: pytest.MonkeyPatch, request: pytest.FixtureRequest
) -> None:
    """Reduce DEAP generation overhead while preserving trial accounting."""
    if not _should_apply_evolutionary_optimizer_shortcuts(request):
        return

    def _fast_run_generation(
        self,
        generation_idx: int,
        population: list[Any],
        initial_prompts: dict[str, chat_prompt.ChatPrompt],
        hof: Any,
        best_primary_score_overall: float,
    ) -> tuple[list[Any], int]:
        _ = (generation_idx, initial_prompts, hof, best_primary_score_overall)
        context = getattr(self, "_test_context", None)
        if context is not None:
            context.trials_completed += 1
            if context.current_best_score is None:
                context.current_best_score = 0.0
        return population, 1

    monkeypatch.setattr(EvolutionaryOptimizer, "_run_generation", _fast_run_generation)


@pytest.fixture(autouse=True)
def _fast_run_optimization(
    monkeypatch: pytest.MonkeyPatch, request: pytest.FixtureRequest
) -> None:
    """Short-circuit run_optimization while keeping trial/accounting semantics."""
    if not _should_apply_evolutionary_optimizer_shortcuts(request):
        return

    def _run_optimization(self, context):  # type: ignore[no-untyped-def]
        self._test_context = context
        if context.validation_dataset is not None:
            context.evaluation_dataset = context.validation_dataset

        self.evaluate(context, context.prompts)

        if context.current_best_prompt is None:
            context.current_best_prompt = context.prompts

        return AlgorithmResult(
            best_prompts=context.current_best_prompt,
            best_score=context.current_best_score or 0.0,
            metadata={},
            history=self.get_history_entries(),
        )

    monkeypatch.setattr(EvolutionaryOptimizer, "run_optimization", _run_optimization)


@pytest.fixture(autouse=True)
def _fast_evaluate(monkeypatch: pytest.MonkeyPatch, request: pytest.FixtureRequest) -> None:
    """Skip display/stop checks while still calling evaluate_prompt."""
    if not _should_apply_evolutionary_optimizer_shortcuts(request):
        return

    def _evaluate(  # type: ignore[no-untyped-def]
        self,
        context,
        prompts,
        experiment_config=None,
    ):
        score = self.evaluate_prompt(
            prompt=prompts,
            dataset=context.evaluation_dataset,
            metric=context.metric,
            agent=context.agent,
            experiment_config=experiment_config,
            n_samples=context.n_samples,
            n_threads=1,
            verbose=0,
        )
        coerced_score = self._coerce_score(score)
        context.trials_completed += 1
        if context.current_best_score is None or coerced_score > context.current_best_score:
            context.current_best_score = coerced_score
            context.current_best_prompt = prompts
        return coerced_score

    monkeypatch.setattr(EvolutionaryOptimizer, "evaluate", _evaluate)

