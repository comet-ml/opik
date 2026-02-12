"""Unit tests for BaseOptimizer validation and small helper behaviors."""

# mypy: disable-error-code=no-untyped-def
from __future__ import annotations

from typing import Any

import pytest

from tests.unit.fixtures.base_optimizer_test_helpers import ConcreteOptimizer
from tests.unit.test_helpers import make_mock_dataset, make_optimization_context
from opik.evaluation.metrics import score_result
from opik.evaluation import evaluation_result as opik_evaluation_result
from opik.evaluation import test_case as opik_test_case
from opik.evaluation import test_result as opik_test_result


class TestValidateOptimizationInputs:
    """Tests for _validate_optimization_inputs method."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_dataset(self, mock_dataset):
        """Use the mock_dataset fixture from conftest."""
        return mock_dataset([{"id": "1", "question": "Q1", "answer": "A1"}])

    @pytest.fixture
    def mock_metric(self):
        def metric(dataset_item, llm_output):
            _ = dataset_item, llm_output
            return 1.0

        return metric

    def test_accepts_valid_single_prompt(
        self, optimizer, simple_chat_prompt, mock_dataset, mock_metric
    ) -> None:
        """Should accept a valid ChatPrompt, Dataset, and metric."""
        mock_ds = make_mock_dataset()

        optimizer._validate_optimization_inputs(
            simple_chat_prompt, mock_ds, mock_metric
        )

    def test_accepts_valid_prompt_dict(
        self, optimizer, simple_chat_prompt, mock_metric
    ) -> None:
        """Should accept a dict of ChatPrompt objects."""
        mock_ds = make_mock_dataset()
        prompt_dict = {"main": simple_chat_prompt}

        optimizer._validate_optimization_inputs(prompt_dict, mock_ds, mock_metric)

    def test_rejects_non_chatprompt(self, optimizer, mock_metric) -> None:
        """Should reject prompt that is not a ChatPrompt."""
        mock_ds = make_mock_dataset()

        with pytest.raises(ValueError, match="ChatPrompt"):
            optimizer._validate_optimization_inputs(
                "not a prompt",  # type: ignore[arg-type]
                mock_ds,
                mock_metric,
            )

    def test_rejects_dict_with_non_chatprompt_values(
        self, optimizer, mock_metric
    ) -> None:
        """Should reject dict containing non-ChatPrompt values."""
        mock_ds = make_mock_dataset()
        invalid_dict = {"main": "not a prompt"}

        with pytest.raises(ValueError, match="ChatPrompt"):
            optimizer._validate_optimization_inputs(
                invalid_dict,  # type: ignore[arg-type]
                mock_ds,
                mock_metric,
            )

    def test_rejects_non_dataset(
        self, optimizer, simple_chat_prompt, mock_metric
    ) -> None:
        """Should reject non-Dataset object."""
        with pytest.raises(ValueError, match="Dataset"):
            optimizer._validate_optimization_inputs(
                simple_chat_prompt,
                "not a dataset",  # type: ignore[arg-type]
                mock_metric,
            )

    def test_rejects_non_callable_metric(self, optimizer, simple_chat_prompt) -> None:
        """Should reject metric that is not callable."""
        mock_ds = make_mock_dataset()

        with pytest.raises(ValueError, match="function"):
            optimizer._validate_optimization_inputs(
                simple_chat_prompt,
                mock_ds,
                "not a function",  # type: ignore[arg-type]
            )

    def test_rejects_multimodal_when_not_supported(
        self, optimizer, multimodal_chat_prompt, mock_metric
    ) -> None:
        """Should reject multimodal prompts when support_content_parts=False."""
        mock_ds = make_mock_dataset()

        with pytest.raises(ValueError, match="content parts"):
            optimizer._validate_optimization_inputs(
                multimodal_chat_prompt,
                mock_ds,
                mock_metric,
                support_content_parts=False,
            )

    def test_accepts_multimodal_when_supported(
        self, optimizer, multimodal_chat_prompt, mock_metric
    ) -> None:
        """Should accept multimodal prompts when support_content_parts=True."""
        mock_ds = make_mock_dataset()

        optimizer._validate_optimization_inputs(
            multimodal_chat_prompt,
            mock_ds,
            mock_metric,
            support_content_parts=True,
        )


class TestSkipAndResultHelpers:
    """Tests for skip-threshold and result helper utilities."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_should_skip_optimization_respects_defaults(self, optimizer) -> None:
        assert optimizer._should_skip_optimization(0.96) is True
        assert optimizer._should_skip_optimization(0.5) is False

    def test_should_skip_optimization_overrides(self, optimizer) -> None:
        assert optimizer._should_skip_optimization(0.5, perfect_score=0.5) is True
        assert (
            optimizer._should_skip_optimization(0.99, skip_perfect_score=False) is False
        )

    def test_select_result_prompts_single(self, optimizer, simple_chat_prompt) -> None:
        best_prompts = {"main": simple_chat_prompt}
        initial_prompts = {"main": simple_chat_prompt}
        result_prompt, result_initial = optimizer._select_result_prompts(
            best_prompts=best_prompts,
            initial_prompts=initial_prompts,
            is_single_prompt_optimization=True,
        )
        assert result_prompt is simple_chat_prompt
        assert result_initial is simple_chat_prompt

    def test_select_result_prompts_bundle(self, optimizer, simple_chat_prompt) -> None:
        best_prompts = {"main": simple_chat_prompt}
        initial_prompts = {"main": simple_chat_prompt}
        result_prompt, result_initial = optimizer._select_result_prompts(
            best_prompts=best_prompts,
            initial_prompts=initial_prompts,
            is_single_prompt_optimization=False,
        )
        assert result_prompt == best_prompts
        assert result_initial == initial_prompts

    def test_build_early_result_defaults(self, optimizer, simple_chat_prompt) -> None:
        result = optimizer._build_early_result(
            optimizer_name="ConcreteOptimizer",
            prompt=simple_chat_prompt,
            initial_prompt=simple_chat_prompt,
            score=0.75,
            metric_name="metric",
            details={"stopped_early": True},
            dataset_id="dataset-id",
            optimization_id="opt-id",
        )
        assert result.score == 0.75
        assert result.metric_name == "metric"
        assert result.initial_score == 0.75
        assert result.history == []
        assert result.details["stopped_early"] is True


class TestMetricRequiredFields:
    """Tests for metric.required_fields validation on the evaluation dataset."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_required_fields_enforced_on_validation_dataset_when_provided(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        mock_opik_client,
    ) -> None:
        """Metrics with required_fields should be validated against evaluation dataset (validation split)."""
        mock_opik_client()
        training_ds = make_mock_dataset(
            [{"id": "1", "question": "Q1", "answer": "A1"}],
            name="training",
            dataset_id="train-123",
        )
        # Missing the required "answer" field on the evaluation dataset
        validation_ds = make_mock_dataset(
            [{"id": "2", "question": "Q2"}],
            name="validation",
            dataset_id="val-123",
        )

        def metric_fn(dataset_item: dict[str, Any], llm_output: str) -> float:
            _ = dataset_item, llm_output
            return 0.0

        metric_fn.__name__ = "metric_with_required_fields"
        metric_fn.required_fields = ("answer",)  # type: ignore[attr-defined]

        with pytest.raises(ValueError, match="requires dataset fields"):
            optimizer._setup_optimization(
                prompt=simple_chat_prompt,
                dataset=training_ds,
                metric=metric_fn,  # type: ignore[arg-type]
                compute_baseline=False,
                validation_dataset=validation_ds,
            )


class TestPreflight:
    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_preflight_fails_on_unresolved_placeholders(
        self,
        optimizer: ConcreteOptimizer,
    ) -> None:
        from opik_optimizer import ChatPrompt

        chat = ChatPrompt(system="sys", user="{question}")
        dataset = make_mock_dataset([{"id": "1", "input": "hello"}])

        def metric_fn(dataset_item: dict[str, Any], llm_output: str) -> float:
            _ = dataset_item, llm_output
            return 1.0

        metric_fn.__name__ = "metric_fn"

        class Agent:
            def invoke_agent(self, **kwargs: Any) -> str:
                _ = kwargs
                return "ok"

        context = make_optimization_context(
            chat,
            dataset=dataset,
            evaluation_dataset=dataset,
            metric=metric_fn,
            agent=Agent(),
        )

        with pytest.raises(ValueError, match="unresolved prompt placeholders"):
            optimizer._run_preflight(context)

    def test_preflight_fails_when_metric_marks_scoring_failed(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        dataset = make_mock_dataset([{"id": "1", "question": "hello", "input": "hello"}])

        def metric_fn(
            dataset_item: dict[str, Any], llm_output: str
        ) -> score_result.ScoreResult:
            _ = dataset_item, llm_output
            return score_result.ScoreResult(
                name="metric_fn",
                value=0.0,
                scoring_failed=True,
                reason="parser failed",
            )

        metric_fn.__name__ = "metric_fn"

        class Agent:
            def invoke_agent(self, **kwargs: Any) -> str:
                _ = kwargs
                return "ok"

        context = make_optimization_context(
            simple_chat_prompt,
            dataset=dataset,
            evaluation_dataset=dataset,
            metric=metric_fn,
            agent=Agent(),
        )

        failed_score = score_result.ScoreResult(
            name="metric_fn",
            value=0.0,
            scoring_failed=True,
            reason="parser failed",
        )
        fake_eval_result = opik_evaluation_result.EvaluationResultOnDictItems(
            test_results=[
                opik_test_result.TestResult(
                    test_case=opik_test_case.TestCase(
                        trace_id="trace-1",
                        dataset_item_id="item-1",
                        task_output={"llm_output": "ok"},
                        dataset_item_content={"id": "1", "question": "hello"},
                    ),
                    score_results=[failed_score],
                    trial_id=0,
                )
            ]
        )
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **_kwargs: fake_eval_result)

        with pytest.raises(ValueError, match="scoring_failed=True"):
            optimizer._run_preflight(context)

    def test_preflight_passes_and_stores_report(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt,
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        dataset = make_mock_dataset([{"id": "1", "question": "hello", "input": "hello"}])

        def metric_fn(dataset_item: dict[str, Any], llm_output: str) -> float:
            _ = dataset_item, llm_output
            return 1.0

        metric_fn.__name__ = "metric_fn"

        class Agent:
            def invoke_agent(self, **kwargs: Any) -> str:
                _ = kwargs
                return "ok"

        context = make_optimization_context(
            simple_chat_prompt,
            dataset=dataset,
            evaluation_dataset=dataset,
            metric=metric_fn,
            agent=Agent(),
        )

        ok_score = score_result.ScoreResult(
            name="metric_fn",
            value=1.0,
            scoring_failed=False,
        )
        fake_eval_result = opik_evaluation_result.EvaluationResultOnDictItems(
            test_results=[
                opik_test_result.TestResult(
                    test_case=opik_test_case.TestCase(
                        trace_id="trace-1",
                        dataset_item_id="item-1",
                        task_output={"llm_output": "ok"},
                        dataset_item_content={"id": "1", "question": "hello"},
                    ),
                    score_results=[ok_score],
                    trial_id=0,
                )
            ]
        )
        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **_kwargs: fake_eval_result)

        optimizer._run_preflight(context)

        assert optimizer._preflight_report is not None
        assert optimizer._preflight_report["status"] == "passed"
