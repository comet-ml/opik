# mypy: disable-error-code=no-untyped-def
"""Guard against candidates that drop the seed prompt's template variables.

Substitution in ChatPrompt.get_messages is a plain str.replace of "{key}", so a
candidate that rewrites a message without the token loses the dataset value with
no error at all. These tests pin the reject-and-revert behaviour that keeps such
a candidate from ever being evaluated as-is.
"""

from typing import Any
from unittest.mock import MagicMock

from opik_optimizer.api_objects import chat_prompt
from opik_optimizer.algorithms.gepa_optimizer import adapter as adapter_module
from opik_optimizer.algorithms.gepa_optimizer.adapter import OpikGEPAAdapter
from opik_optimizer.algorithms.gepa_optimizer.ops import candidate_ops
from opik_optimizer.algorithms.gepa_optimizer.types import OpikDataInst
from tests.unit.fixtures.builders import make_mock_dataset, make_simple_metric


def _prompt(messages: list[dict[str, str]]) -> chat_prompt.ChatPrompt:
    return chat_prompt.ChatPrompt(name="p", messages=messages)


def _make_adapter(
    base_prompts: dict[str, chat_prompt.ChatPrompt],
    allowed_roles: list[str] | None = None,
) -> OpikGEPAAdapter:
    context = MagicMock()
    context.extra_params = (
        {"optimizable_roles": allowed_roles} if allowed_roles is not None else {}
    )
    return OpikGEPAAdapter(
        base_prompts=base_prompts,
        agent=MagicMock(),
        optimizer=MagicMock(),
        context=context,
        metric=make_simple_metric(),
        dataset=make_mock_dataset([{"id": "t1", "question": "q", "answer": "a"}]),
        experiment_config=None,
    )


class TestExtractPlaceholders:
    def test_extracts_identifier_tokens(self) -> None:
        assert candidate_ops.extract_placeholders(
            "Answer {question} for {user_id} and {a.b} and {user-name}"
        ) == {"question", "user_id", "a.b", "user-name"}

    def test_ignores_json_and_code_braces(self) -> None:
        """A prompt full of JSON must not read as a prompt full of variables.

        Otherwise the guard would revert every legitimate reformatting edit and
        stall the optimization entirely.
        """
        assert (
            candidate_ops.extract_placeholders(
                'Reply as {"answer": 1} or {} or { spaced } or {2bad}'
            )
            == set()
        )

    def test_reads_text_parts_of_multimodal_content(self) -> None:
        content = [
            {"type": "text", "text": "Describe {image_topic}"},
            {"type": "image_url", "image_url": {"url": "http://x/y.png"}},
        ]
        assert candidate_ops.extract_placeholders(content) == {"image_topic"}

    def test_known_dataset_keys_extend_the_identifier_shape(self) -> None:
        """A dataset column is substitutable whatever its shape, so its literal
        "{key}" token counts as a variable when the caller names the columns."""
        assert candidate_ops.extract_placeholders(
            "Answer {my key} about {question}", known_keys={"my key", "question"}
        ) == {"my key", "question"}

    def test_known_keys_require_the_exact_literal_token(self) -> None:
        """JSON that merely mentions a column name is still not a variable."""
        assert (
            candidate_ops.extract_placeholders(
                'Reply as {"answer": 1}', known_keys={"answer"}
            )
            == set()
        )


class TestEnforcePlaceholderPreservation:
    def test_reverts_message_that_dropped_a_variable(self) -> None:
        original = [{"role": "user", "content": "Answer {question}"}]
        guarded, reverted = candidate_ops.enforce_placeholder_preservation(
            original_messages=original,
            new_messages=[{"role": "user", "content": "Answer the capital of France"}],
            prompt_name="p",
        )
        assert guarded == original
        assert reverted == ["p_user_0"]

    def test_keeps_edit_that_preserves_the_variable(self) -> None:
        original = [{"role": "user", "content": "Answer {question}"}]
        new = [
            {"role": "user", "content": "Think step by step, then answer {question}"}
        ]
        guarded, reverted = candidate_ops.enforce_placeholder_preservation(
            original_messages=original, new_messages=new, prompt_name="p"
        )
        assert guarded == new
        assert reverted == []

    def test_variable_moved_between_messages_is_not_a_loss(self) -> None:
        """Substitution runs over every message, so a relocated variable is fine."""
        original = [
            {"role": "system", "content": "Answer {question}"},
            {"role": "user", "content": "Go"},
        ]
        new = [
            {"role": "system", "content": "Be concise."},
            {"role": "user", "content": "Go: {question}"},
        ]
        guarded, reverted = candidate_ops.enforce_placeholder_preservation(
            original_messages=original, new_messages=new, prompt_name="p"
        )
        assert guarded == new
        assert reverted == []

    def test_reverts_only_the_offending_message(self) -> None:
        original = [
            {"role": "system", "content": "Use {context}"},
            {"role": "user", "content": "Answer {question}"},
        ]
        new = [
            {"role": "system", "content": "Use {context} carefully"},  # good edit
            {"role": "user", "content": "Answer it"},  # dropped {question}
        ]
        guarded, reverted = candidate_ops.enforce_placeholder_preservation(
            original_messages=original, new_messages=new, prompt_name="p"
        )
        assert guarded[0]["content"] == "Use {context} carefully"
        assert guarded[1]["content"] == "Answer {question}"
        assert reverted == ["p_user_1"]

    def test_no_variables_in_seed_is_a_no_op(self) -> None:
        original = [{"role": "system", "content": "Be nice"}]
        new = [{"role": "system", "content": "Be very nice"}]
        guarded, reverted = candidate_ops.enforce_placeholder_preservation(
            original_messages=original, new_messages=new, prompt_name="p"
        )
        assert guarded == new
        assert reverted == []

    def test_known_keys_protect_non_identifier_placeholders(self) -> None:
        original = [{"role": "user", "content": "Answer {my key}"}]
        guarded, reverted = candidate_ops.enforce_placeholder_preservation(
            original_messages=original,
            new_messages=[{"role": "user", "content": "Answer it"}],
            prompt_name="p",
            known_keys={"my key"},
        )
        assert guarded == original
        assert reverted == ["p_user_0"]

    def test_revert_keeps_extra_message_fields(self) -> None:
        """A revert restores content without stripping other message fields."""
        original = [{"role": "user", "name": "asker", "content": "Answer {question}"}]
        guarded, reverted = candidate_ops.enforce_placeholder_preservation(
            original_messages=original,
            new_messages=[{"role": "user", "content": "Answer it"}],
            prompt_name="p",
        )
        assert guarded[0] == original[0]
        assert reverted == ["p_user_0"]


class TestRebuildAppliesGuard:
    """The guard must hold on every rebuild path, not just one."""

    def test_shared_rebuild_rejects_stripped_variable(self) -> None:
        prompt = _prompt(
            [
                {"role": "system", "content": "You answer questions."},
                {"role": "user", "content": "Answer {question}"},
            ]
        )
        rebuilt, reverted = candidate_ops.rebuild_prompts_from_candidate(
            base_prompts={"p": prompt},
            candidate={
                "p_system_0": "You are an expert geographer.",
                "p_user_1": "What is the capital of France?",  # inlined the example
            },
        )
        messages = rebuilt["p"].get_messages()
        assert messages[0]["content"] == "You are an expert geographer."  # kept
        assert messages[1]["content"] == "Answer {question}"  # rejected
        assert reverted == ["p_user_1"]

    def test_shared_rebuild_honours_known_dataset_keys(self) -> None:
        prompt = _prompt([{"role": "user", "content": "Answer {my key}"}])
        rebuilt, reverted = candidate_ops.rebuild_prompts_from_candidate(
            base_prompts={"p": prompt},
            candidate={"p_user_0": "Answer it"},
            known_placeholder_keys={"my key"},
        )
        assert rebuilt["p"].get_messages()[0]["content"] == "Answer {my key}"
        assert reverted == ["p_user_0"]

    def test_adapter_rebuild_rejects_and_records(self) -> None:
        prompt = _prompt([{"role": "user", "content": "Answer {question}"}])
        adapter = _make_adapter({"p": prompt})

        rebuilt, reverted = adapter._rebuild_prompts_from_candidate(
            {"p_user_0": "What is the capital of France?"}
        )

        assert rebuilt["p"].get_messages()[0]["content"] == "Answer {question}"
        assert reverted == ["p_user_0"]

    def test_adapter_records_nothing_when_candidate_is_clean(self) -> None:
        prompt = _prompt([{"role": "user", "content": "Answer {question}"}])
        adapter = _make_adapter({"p": prompt})

        rebuilt, reverted = adapter._rebuild_prompts_from_candidate(
            {"p_user_0": "Answer concisely: {question}"}
        )

        assert (
            rebuilt["p"].get_messages()[0]["content"] == "Answer concisely: {question}"
        )
        assert reverted == []

    def test_adapter_protects_non_identifier_dataset_keys(self) -> None:
        """The dataset's columns are authoritative: "{my key}" is outside the
        identifier regex, but the adapter knows the column exists and
        substitution would replace its literal token, so dropping it is a loss."""
        prompt = _prompt([{"role": "user", "content": "Answer {my key}"}])
        adapter = _make_adapter({"p": prompt})
        adapter._known_placeholder_keys = {"id", "my key", "answer"}

        rebuilt, reverted = adapter._rebuild_prompts_from_candidate(
            {"p_user_0": "What is the capital of France?"}
        )

        assert rebuilt["p"].get_messages()[0]["content"] == "Answer {my key}"
        assert reverted == ["p_user_0"]

    def test_adapter_learns_known_keys_from_the_dataset(self) -> None:
        """__init__ collects the columns itself — no caller wiring required."""
        prompt = _prompt([{"role": "user", "content": "Answer {question}"}])
        adapter = _make_adapter({"p": prompt})

        assert adapter._known_placeholder_keys == {"id", "question", "answer"}

    def test_metadata_records_the_rejection_through_evaluate(
        self, monkeypatch: Any
    ) -> None:
        """The rejection must reach the trial's experiment config, not just logs.

        Logs alone are not "recorded" for a user looking at a finished run, so
        this walks the real evaluate() path and inspects what is handed to
        prepare_experiment_config.
        """
        captured = self._run_evaluate(
            monkeypatch, candidate={"p_user_0": "What is the capital of France?"}
        )
        gepa_meta = captured["configuration_updates"]["gepa"]
        assert gepa_meta["rejected_components_missing_variables"] == ["p_user_0"]

    def test_metadata_omits_the_key_when_nothing_was_rejected(
        self, monkeypatch: Any
    ) -> None:
        """drop_none must keep clean runs free of a noisy always-null field."""
        captured = self._run_evaluate(
            monkeypatch, candidate={"p_user_0": "Answer briefly: {question}"}
        )
        gepa_meta = captured["configuration_updates"]["gepa"]
        assert "rejected_components_missing_variables" not in gepa_meta

    @staticmethod
    def _run_evaluate(monkeypatch: Any, candidate: dict[str, str]) -> dict[str, Any]:
        """Drive adapter.evaluate() with scoring stubbed, returning config kwargs.

        The rebuild itself is deliberately NOT stubbed — it is the code under
        test here.
        """
        prompt = _prompt([{"role": "user", "content": "Answer {question}"}])
        adapter = _make_adapter({"p": prompt})

        def fake_evaluate_with_result(**kwargs: Any) -> tuple[float, Any]:
            eval_result = MagicMock()
            eval_result.test_results = []
            return 1.0, eval_result

        captured: dict[str, Any] = {}

        def fake_prepare_experiment_config(**kwargs: Any) -> dict[str, Any]:
            captured.update(kwargs)
            return {"project_name": "test"}

        monkeypatch.setattr(
            adapter_module.task_evaluator,
            "evaluate_with_result",
            fake_evaluate_with_result,
        )
        monkeypatch.setattr(
            adapter_module, "prepare_experiment_config", fake_prepare_experiment_config
        )

        batch = [
            OpikDataInst(
                input_text="q",
                answer="a",
                additional_context={},
                opik_item={"id": "t1", "question": "q", "answer": "a"},
            )
        ]
        adapter.evaluate(batch, candidate=candidate, capture_traces=False)
        return captured

    def test_guarded_prompt_still_substitutes_the_dataset_value(self) -> None:
        """End-to-end point of the guard: the user's input reaches the model."""
        prompt = _prompt([{"role": "user", "content": "Answer {question}"}])
        adapter = _make_adapter({"p": prompt})

        rebuilt, _ = adapter._rebuild_prompts_from_candidate(
            {"p_user_0": "Answer the capital of France"}
        )

        rendered = rebuilt["p"].get_messages({"question": "What is 2+2?"})
        assert rendered[0]["content"] == "Answer What is 2+2?"
