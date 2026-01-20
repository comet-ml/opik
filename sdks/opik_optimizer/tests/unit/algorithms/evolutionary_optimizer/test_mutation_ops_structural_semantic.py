import pytest
from typing import Any

import opik_optimizer
from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.evolutionary_optimizer.ops import mutation_ops
from tests.unit.test_helpers import make_fake_llm_call
from tests.unit.algorithms.evolutionary_optimizer._mutation_test_helpers import (
    force_random,
    patch_get_synonym,
)

pytestmark = pytest.mark.usefixtures("suppress_expected_optimizer_warnings")


class TestAdaptiveMutationRate:
    """Tests for compute_adaptive_mutation_rate function."""

    def test_increases_rate_when_diversity_low(self) -> None:
        current_rate = 0.2
        best_fitness_history = [1.0, 1.0]
        current_population = [{"prompt": "same"} for _ in range(3)]

        adjusted_rate, generations = mutation_ops.compute_adaptive_mutation_rate(
            current_rate=current_rate,
            best_fitness_history=best_fitness_history,
            current_population=current_population,
            generations_without_improvement=0,
            adaptive_mutation=True,
            restart_threshold=0.01,
            restart_generations=5,
            min_rate=0.05,
            max_rate=1.0,
            diversity_threshold=0.7,
        )

        assert adjusted_rate > current_rate
        assert adjusted_rate <= 1.0
        assert generations == 1

    def test_uses_base_rate_when_diversity_high_and_improving(self) -> None:
        current_rate = 0.4
        best_fitness_history = [1.0, 1.2]
        current_population = [{"prompt": "one"}, {"prompt": "two completely different"}]

        adjusted_rate, generations = mutation_ops.compute_adaptive_mutation_rate(
            current_rate=current_rate,
            best_fitness_history=best_fitness_history,
            current_population=current_population,
            generations_without_improvement=2,
            adaptive_mutation=True,
            restart_threshold=0.01,
            restart_generations=5,
            min_rate=0.1,
            max_rate=1.0,
            diversity_threshold=0.1,
        )

        assert adjusted_rate == max(current_rate * 0.8, 0.1)
        assert generations == 0


class TestStructuralMutation:
    """Tests for _structural_mutation function."""

    def test_shuffle_sentences_path(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        force_random(monkeypatch, random_value=0.1)

        prompt = ChatPrompt(
            system="First sentence. Second sentence. Third sentence.",
            user="Question here.",
        )

        result = mutation_ops._structural_mutation(
            prompt=prompt, model="gpt-4", model_parameters={}, prompts=evo_prompts
        )

        assert isinstance(result, ChatPrompt)

    def test_combine_sentences_path(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        call_count = {"n": 0}

        def controlled_random() -> float:
            call_count["n"] += 1
            return 0.4

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.random",
            controlled_random,
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.randint",
            lambda a, b: 0,
        )

        prompt = ChatPrompt(
            system="First sentence. Second sentence. Third sentence.",
            user="Question here.",
        )

        result = mutation_ops._structural_mutation(
            prompt=prompt, model="gpt-4", model_parameters={}, prompts=evo_prompts
        )

        assert isinstance(result, ChatPrompt)

    def test_split_sentences_path(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        force_random(monkeypatch, random_value=0.8)
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.randint",
            lambda a, b: a + (b - a) // 2 if b > a else a,
        )

        prompt = ChatPrompt(
            system="This is a longer sentence with many words here.",
            user="Question here.",
        )

        result = mutation_ops._structural_mutation(
            prompt=prompt, model="gpt-4", model_parameters={}, prompts=evo_prompts
        )

        assert isinstance(result, ChatPrompt)

    def test_fallback_to_word_mutation_for_single_sentence(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        force_random(monkeypatch, random_value=0.1, randint_value=0)
        patch_get_synonym(monkeypatch, return_value="modified")

        prompt = ChatPrompt(system="Single sentence here", user="Question")

        result = mutation_ops._structural_mutation(
            prompt=prompt, model="gpt-4", model_parameters={}, prompts=evo_prompts
        )

        assert isinstance(result, ChatPrompt)


class TestRadicalInnovationMutation:
    """Tests for _radical_innovation_mutation function."""

    def test_returns_new_prompt_on_success(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            make_fake_llm_call(
                '[{"role": "system", "content": "New innovative prompt"}, {"role": "user", "content": "{input}"}]'
            ),
        )

        prompt = ChatPrompt(system="Original", user="Question")
        initial_prompt = ChatPrompt(system="Initial", user="Task")

        result = mutation_ops._radical_innovation_mutation(
            prompt=prompt,
            initial_prompt=initial_prompt,
            model="gpt-4",
            model_parameters={},
            output_style_guidance="Be concise",
            prompts=evo_prompts,
        )

        assert isinstance(result, ChatPrompt)
        messages = result.get_messages()
        assert any("innovative" in str(m.get("content", "")).lower() for m in messages)

    def test_returns_original_on_parse_error(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            make_fake_llm_call("Not valid JSON at all"),
        )

        prompt = ChatPrompt(system="Original", user="Question")
        initial_prompt = ChatPrompt(system="Initial", user="Task")

        result = mutation_ops._radical_innovation_mutation(
            prompt=prompt,
            initial_prompt=initial_prompt,
            model="gpt-4",
            model_parameters={},
            output_style_guidance="Be concise",
            prompts=evo_prompts,
        )

        assert result is prompt

    def test_returns_original_on_llm_error(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            make_fake_llm_call(raises=Exception("LLM API error")),
        )

        prompt = ChatPrompt(system="Original", user="Question")
        initial_prompt = ChatPrompt(system="Initial", user="Task")

        result = mutation_ops._radical_innovation_mutation(
            prompt=prompt,
            initial_prompt=initial_prompt,
            model="gpt-4",
            model_parameters={},
            output_style_guidance="Be concise",
            prompts=evo_prompts,
        )

        assert result is prompt


class TestSemanticMutation:
    """Tests for _semantic_mutation function."""

    def test_triggers_radical_innovation_randomly(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.random",
            lambda: 0.05,
        )

        def fake_radical_innovation(**kwargs: Any) -> ChatPrompt:
            _ = kwargs
            return ChatPrompt(system="Radical innovation", user="New")

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops._radical_innovation_mutation",
            fake_radical_innovation,
        )

        prompt = ChatPrompt(system="Original", user="Question")

        result = mutation_ops._semantic_mutation(
            prompt=prompt,
            initial_prompt=prompt,
            model="gpt-4",
            model_parameters={},
            verbose=0,
            output_style_guidance="Be concise",
            prompts=evo_prompts,
        )

        assert isinstance(result, ChatPrompt)

    def test_returns_original_on_error(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.random",
            lambda: 0.5,
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.choice",
            lambda seq: "rephrase",
        )

        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            make_fake_llm_call(raises=Exception("LLM error")),
        )

        captured: dict[str, Any] = {}

        def fake_display_error(message: str, verbose: int = 1) -> None:
            _ = message
            captured["called"] = True

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.reporting.display_error",
            fake_display_error,
        )

        prompt = ChatPrompt(system="Original", user="Question")

        result = mutation_ops._semantic_mutation(
            prompt=prompt,
            initial_prompt=prompt,
            model="gpt-4",
            model_parameters={},
            verbose=1,
            output_style_guidance="Be concise",
            prompts=evo_prompts,
        )

        assert result is prompt
        assert captured.get("called") is True


class TestDeapMutation:
    """Tests for deap_mutation function."""

    def test_mutates_single_prompt(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        from deap import creator

        if not hasattr(creator, "Individual"):
            creator.create("Individual", dict, fitness=None)

        call_count = {"n": 0}

        def controlled_random() -> float:
            call_count["n"] += 1
            if call_count["n"] <= 2:
                return 0.5
            return 0.3

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.random",
            controlled_random,
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.choice",
            lambda seq: seq[0] if seq else "rephrase",
        )

        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            make_fake_llm_call(
                '[{"role": "system", "content": "Mutated"}, {"role": "user", "content": "Question"}]'
            ),
        )

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.reporting.display_success",
            lambda *_a, **_k: None,
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.helpers.calculate_population_diversity",
            lambda *_a, **_k: 0.5,
        )

        individual = creator.Individual(
            {
                "main": [
                    {"role": "system", "content": "Original"},
                    {"role": "user", "content": "Q"},
                ]
            }
        )
        initial_prompts = {"main": ChatPrompt(system="Initial", user="Q")}

        result = mutation_ops.deap_mutation(
            individual=individual,
            current_population=[individual],
            output_style_guidance="Be concise",
            initial_prompts=initial_prompts,
            model="gpt-4",
            model_parameters={},
            diversity_threshold=0.3,
            optimization_id="opt-123",
            verbose=0,
            prompts=evo_prompts,
        )

        assert hasattr(result, "keys")
        assert "main" in result


def test_semantic_mutation_invalid_json_response(
    monkeypatch: pytest.MonkeyPatch,
    evo_prompts: Any,
) -> None:
    def fake_call_model(
        *,
        messages: list[dict[str, str]],
        is_reasoning: bool,
        model: str,
        model_parameters: dict[str, Any],
        **_kwargs: Any,
    ) -> str:
        _ = (messages, is_reasoning, model, model_parameters)
        return (
            "[{'role': 'system', 'content': 'Provide a brief and direct answer to the question.'}, "
            "{'role': 'user', 'content': '{question}'}]"
        )

    monkeypatch.setattr("opik_optimizer.core.llm_calls.call_model", fake_call_model)

    monkeypatch.setattr(
        "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.random",
        lambda: 0.5,
    )
    monkeypatch.setattr(
        "opik_optimizer.algorithms.evolutionary_optimizer.helpers.get_task_description_for_llm",
        lambda initial_prompt: "Summarize task",
    )
    monkeypatch.setattr(
        "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.choice",
        lambda seq: seq[0],
    )

    captured: dict[str, object] = {}

    def fake_display_error(message: str, verbose: int = 1) -> None:
        captured["message"] = message
        captured["verbose"] = verbose

    monkeypatch.setattr(
        "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.reporting.display_error",
        fake_display_error,
    )

    original_prompt = opik_optimizer.ChatPrompt(
        messages=[
            {"role": "system", "content": "Provide factual answers."},
            {"role": "user", "content": "What is the capital of France?"},
        ]
    )

    result = mutation_ops._semantic_mutation(
        prompt=original_prompt,
        initial_prompt=original_prompt,
        output_style_guidance="Keep answers brief.",
        model="openai/gpt-5-mini",
        model_parameters={},
        verbose=1,
        prompts=evo_prompts,
    )

    assert result is not original_prompt
    assert captured == {}
    assert result.get_messages() == [
        {
            "role": "system",
            "content": "Provide a brief and direct answer to the question.",
        },
        {"role": "user", "content": "{question}"},
    ]
