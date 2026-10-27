# mypy: disable-error-code=no-untyped-def

from typing import Any

import pytest

from opik_optimizer import (
    ChatPrompt,
    FewShotBayesianOptimizer,
)
from opik_optimizer.agents.optimizable_agent import OptimizableAgent
from tests.unit.test_helpers import (
    make_mock_dataset,
    make_simple_metric,
    STANDARD_DATASET_ITEMS,
)


class TestFewShotBayesianOptimizerOptimizePrompt:
    def test_sanitized_prompt_name_collision_raises(self) -> None:
        optimizer = FewShotBayesianOptimizer(model="gpt-4o-mini", verbose=0, seed=42)

        with pytest.raises(
            ValueError, match="Prompt name collision after sanitization"
        ):
            optimizer._sanitize_prompt_field_names(["chat-prompt", "chat_prompt"])

    def test_custom_task_respects_allow_tool_use(self) -> None:
        class AgentSpy(OptimizableAgent):
            def __init__(self) -> None:
                self.last_allow_tool_use: bool | None = None
                self.project_name = "test"

            def init_llm(self) -> None:
                return None

            def invoke_agent(
                self,
                prompts: dict[str, ChatPrompt],
                dataset_item: dict[str, Any],
                allow_tool_use: bool = False,
                seed: int | None = None,
            ) -> str:
                _ = prompts, dataset_item, seed
                self.last_allow_tool_use = allow_tool_use
                return "ok"

        optimizer = FewShotBayesianOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        agent = AgentSpy()
        prompts = {"main": ChatPrompt(system="system", user="{question}")}
        task = optimizer._build_task_from_messages(
            agent=agent,
            prompts=prompts,
            few_shot_examples="Q: a\nA: b",
            allow_tool_use=False,
        )

        task({"question": "Q1"})
        assert agent.last_allow_tool_use is False


class TestFewShotBayesianOptimizerEvaluate:
    def test_custom_task_evaluation_records_scoring_health(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        from opik.evaluation import evaluation_result, test_case, test_result
        from opik.evaluation.metrics import score_result

        from opik_optimizer.core import evaluation as core_evaluation
        from tests.unit.test_helpers import make_optimization_context

        def metric(dataset_item: dict[str, Any], llm_output: str) -> float:
            return 0.0

        failed = evaluation_result.EvaluationResult(
            experiment_id="exp",
            dataset_id="ds",
            experiment_name="exp",
            test_results=[
                test_result.TestResult(
                    test_case=test_case.TestCase(
                        trace_id="t",
                        dataset_item_id="i",
                        task_output={},
                        dataset_item_content={},
                    ),
                    score_results=[
                        score_result.ScoreResult(
                            name="metric", value=0.0, scoring_failed=True
                        )
                    ],
                    trial_id=0,
                )
            ],
            experiment_url=None,
            trial_count=1,
        )
        monkeypatch.setattr(
            core_evaluation,
            "evaluate",
            lambda **kwargs: failed if kwargs.get("return_evaluation_result") else 0.0,
        )
        optimizer = FewShotBayesianOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        optimizer._custom_evaluated_task = lambda item: {"llm_output": "x"}
        context = make_optimization_context(
            ChatPrompt(system="system", user="{question}"), metric=metric
        )

        optimizer.evaluate(context, context.prompts)

        # The failed-scoring guard reads this; it stayed unset when the override
        # coerced the result to a float.
        assert context.scoring_health == {"failed_count": 1, "total_count": 1}


class TestFewShotBayesianOptimizerOptuna:
    def test_stop_ends_the_study_without_pruned_trials(
        self, mock_optimization_context, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        import optuna

        mock_optimization_context()
        optimizer = FewShotBayesianOptimizer(
            model="gpt-4o-mini", verbose=0, seed=42, min_examples=1, max_examples=2
        )
        scores = iter([0.5])  # baseline; every trial after that is perfect
        monkeypatch.setattr(
            optimizer, "evaluate_prompt", lambda **kwargs: next(scores, 1.0)
        )
        monkeypatch.setattr(
            optimizer,
            "_create_few_shot_prompt_template",
            lambda **kwargs: (
                {name: p.copy() for name, p in kwargs["prompts"].items()},
                "Examples:\n{examples}",
            ),
        )
        studies: list[optuna.Study] = []
        real_create_study = optuna.create_study

        def capture_study(**kwargs: Any) -> optuna.Study:
            studies.append(real_create_study(**kwargs))
            return studies[-1]

        monkeypatch.setattr(optuna, "create_study", capture_study)

        result = optimizer.optimize_prompt(
            prompt=ChatPrompt(system="Test", user="{question}"),
            dataset=make_mock_dataset(
                STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
            ),
            metric=make_simple_metric(),
            max_trials=5,
        )

        assert result.details["finish_reason"] == "perfect_score"
        assert [trial.state for trial in studies[0].trials] == [
            optuna.trial.TrialState.COMPLETE
        ]
