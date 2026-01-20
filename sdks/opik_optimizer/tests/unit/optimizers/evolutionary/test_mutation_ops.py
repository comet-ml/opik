import pytest
from typing import Any, cast
from opik_optimizer.algorithms.evolutionary_optimizer.ops import mutation_ops
import opik_optimizer
from opik_optimizer import ChatPrompt
from opik_optimizer.api_objects.types import Content
from tests.unit.test_helpers import make_fake_llm_call

pytestmark = pytest.mark.usefixtures("suppress_expected_optimizer_warnings")


class TestGetSynonym:
    """Tests for _get_synonym function."""

    def test_returns_synonym_from_llm(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model", make_fake_llm_call("quick")
        )

        result = mutation_ops._get_synonym(
            word="fast", model="gpt-4", model_parameters={}, prompts=evo_prompts
        )
        assert result == "quick"

    def test_returns_original_word_on_error(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            make_fake_llm_call(raises=Exception("API error")),
        )

        result = mutation_ops._get_synonym(
            word="fast", model="gpt-4", model_parameters={}, prompts=evo_prompts
        )
        assert result == "fast"

    def test_strips_whitespace_from_response(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            make_fake_llm_call("  quick  \n"),
        )

        result = mutation_ops._get_synonym(
            word="fast", model="gpt-4", model_parameters={}, prompts=evo_prompts
        )
        assert result == "quick"


class TestModifyPhrase:
    """Tests for _modify_phrase function."""

    def test_returns_modified_phrase_from_llm(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model", make_fake_llm_call("rapidly")
        )

        result = mutation_ops._modify_phrase(
            phrase="quickly", model="gpt-4", model_parameters={}, prompts=evo_prompts
        )
        assert result == "rapidly"

    def test_returns_original_phrase_on_error(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            make_fake_llm_call(raises=Exception("API error")),
        )

        result = mutation_ops._modify_phrase(
            phrase="quickly", model="gpt-4", model_parameters={}, prompts=evo_prompts
        )
        assert result == "quickly"


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
        current_population = [
            {"prompt": "one"},
            {"prompt": "two completely different"},
        ]

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


class TestWordLevelMutation:
    """Tests for _word_level_mutation function."""

    def test_returns_original_for_single_word(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        content = "Hello"
        result = mutation_ops._word_level_mutation(
            msg_content=content, model="gpt-4", model_parameters={}, prompts=evo_prompts
        )
        assert result == content

    def test_synonym_mutation_path(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        # Force the synonym mutation path (random < 0.3)
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.random",
            lambda: 0.1,
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.randint",
            lambda a, b: 0,
        )

        def fake_get_synonym(**kwargs: Any) -> str:
            return "great"

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops._get_synonym",
            fake_get_synonym,
        )

        result = mutation_ops._word_level_mutation(
            msg_content="Hello world",
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
        )
        assert "great" in result or "world" in result

    def test_word_swap_mutation_path(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        # Force the word swap path (0.3 <= random < 0.6)
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.random",
            lambda: 0.4,
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.sample",
            lambda seq, k: [0, 2],
        )

        result = mutation_ops._word_level_mutation(
            msg_content="Hello beautiful world",
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
        )
        # Words should be swapped
        assert isinstance(result, str)

    def test_modify_phrase_mutation_path(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        # Force the modify phrase path (random >= 0.6)
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.random",
            lambda: 0.8,
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.randint",
            lambda a, b: 0,
        )

        def fake_modify_phrase(**kwargs: Any) -> str:
            return "Hi"

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops._modify_phrase",
            fake_modify_phrase,
        )

        result = mutation_ops._word_level_mutation(
            msg_content="Hello world",
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
        )
        assert isinstance(result, str)

    def test_handles_content_parts(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        # Force synonym path
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.random",
            lambda: 0.1,
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.randint",
            lambda a, b: 0,
        )

        def fake_get_synonym(**kwargs: Any) -> str:
            return "Greetings"

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops._get_synonym",
            fake_get_synonym,
        )

        content_parts: Content = cast(
            Content,
            [
                {"type": "text", "text": "Hello world"},
                {
                    "type": "image_url",
                    "image_url": {"url": "data:image/png;base64,abc"},
                },
            ],
        )

        result = mutation_ops._word_level_mutation(
            msg_content=content_parts,
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
        )
        # Should preserve structure
        assert isinstance(result, list)


class TestWordLevelMutationPrompt:
    """Tests for _word_level_mutation_prompt function."""

    def test_mutates_all_messages(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.random",
            lambda: 0.1,
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.randint",
            lambda a, b: 0,
        )

        def fake_get_synonym(**kwargs: Any) -> str:
            return "modified"

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops._get_synonym",
            fake_get_synonym,
        )

        prompt = ChatPrompt(
            system="You are helpful.",
            user="Answer the question.",
        )

        result = mutation_ops._word_level_mutation_prompt(
            prompt=prompt,
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
        )

        assert isinstance(result, ChatPrompt)
        assert len(result.get_messages()) == 2


class TestStructuralMutation:
    """Tests for _structural_mutation function."""

    def test_shuffle_sentences_path(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        # Force shuffle path (random < 0.3)
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.random",
            lambda: 0.1,
        )

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
        # Force combine path (0.3 <= random < 0.6)
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
        # Force split path (random >= 0.6)
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.random",
            lambda: 0.8,
        )
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
        # For single sentence, should fall back to word-level mutation
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.random",
            lambda: 0.1,
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.randint",
            lambda a, b: 0,
        )

        def fake_get_synonym(**kwargs: Any) -> str:
            return "modified"

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops._get_synonym",
            fake_get_synonym,
        )

        prompt = ChatPrompt(
            system="Single sentence here",  # No periods
            user="Question",
        )

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

        # Should return original prompt on error
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

        # Should return original prompt on error
        assert result is prompt


class TestSemanticMutation:
    """Tests for _semantic_mutation function."""

    def test_triggers_radical_innovation_randomly(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        # Force radical innovation path (random < 0.1)
        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.random.random",
            lambda: 0.05,
        )

        def fake_radical_innovation(**kwargs: Any) -> ChatPrompt:
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
        # Skip radical innovation
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
        # Setup DEAP creator
        from deap import creator

        if not hasattr(creator, "Individual"):
            creator.create("Individual", dict, fitness=None)

        # Skip radical and use semantic mutation
        call_count = {"n": 0}

        def controlled_random() -> float:
            call_count["n"] += 1
            if call_count["n"] <= 2:
                return 0.5  # Skip radical, trigger semantic
            return 0.3  # For other random calls

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

        def fake_display_success(message: str, verbose: int = 1) -> None:
            pass

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.reporting.display_success",
            fake_display_success,
        )

        def fake_diversity(*args: Any, **kwargs: Any) -> float:
            return 0.5

        monkeypatch.setattr(
            "opik_optimizer.algorithms.evolutionary_optimizer.ops.mutation_ops.helpers.calculate_population_diversity",
            fake_diversity,
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
        # Model responded with a Python repr instead of strict JSON
        return "[{'role': 'system', 'content': 'Provide a brief and direct answer to the question.'}, {'role': 'user', 'content': '{question}'}]"

    monkeypatch.setattr(
        "opik_optimizer.core.llm_calls.call_model",
        fake_call_model,
    )

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
