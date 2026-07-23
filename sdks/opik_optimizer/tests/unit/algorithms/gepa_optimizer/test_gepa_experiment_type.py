# mypy: disable-error-code=no-untyped-def

from typing import Any
from unittest.mock import MagicMock

import pytest

from opik_optimizer.algorithms.gepa_optimizer.adapter import OpikGEPAAdapter
from tests.unit.fixtures.builders import make_mock_dataset, make_simple_metric


def _make_adapter(gepa_val_item_ids: set[str] | None) -> OpikGEPAAdapter:
    context = MagicMock()
    context.extra_params = {}
    return OpikGEPAAdapter(
        base_prompts={},
        agent=MagicMock(),
        optimizer=MagicMock(),
        context=context,
        metric=make_simple_metric(),
        dataset=make_mock_dataset(
            [{"id": "t1", "question": "q", "answer": "a"}],
        ),
        experiment_config=None,
        validation_dataset=None,
        gepa_val_item_ids=gepa_val_item_ids,
    )


class TestClassifyExperimentType:
    @pytest.mark.parametrize(
        "dataset_item_ids,capture_traces,missing_ids,expected",
        [
            # Full valset batch without traces -> full evaluation.
            (["v1", "v2"], False, False, "trial"),
            # Same ids in another order -> still the full valset.
            (["v2", "v1"], False, False, "trial"),
            # Subset of the valset -> mini-batch screening.
            (["v1"], False, False, "mini-batch"),
            # Train mini-batch ids -> mini-batch.
            (["t1", "t2"], False, False, "mini-batch"),
            # Parent reflection evals capture traces and are always mini-batches,
            # even when they happen to cover the full valset.
            (["v1", "v2"], True, False, "mini-batch"),
            # Unknown ids -> legacy "trial" label (safe fallback).
            (["v1", "v2"], False, True, "trial"),
        ],
    )
    def test_classification(
        self,
        dataset_item_ids: list[str],
        capture_traces: bool,
        missing_ids: bool,
        expected: str,
    ) -> None:
        adapter = _make_adapter({"v1", "v2"})
        assert (
            adapter._classify_experiment_type(
                dataset_item_ids=dataset_item_ids,
                capture_traces=capture_traces,
                missing_ids=missing_ids,
            )
            == expected
        )

    def test_no_valset_ids_falls_back_to_trial(self) -> None:
        """Without known valset ids every eval keeps today's 'trial' label."""
        adapter = _make_adapter(None)
        for capture_traces in (False, True):
            assert (
                adapter._classify_experiment_type(
                    dataset_item_ids=["t1"],
                    capture_traces=capture_traces,
                    missing_ids=False,
                )
                == "trial"
            )

    def test_minibatch_equal_to_valset_counts_as_full_eval(self) -> None:
        """A candidate 'mini-batch' that covers the entire valset IS a full
        evaluation by construction; labeling it 'trial' is correct."""
        adapter = _make_adapter({"a", "b"})
        assert (
            adapter._classify_experiment_type(
                dataset_item_ids=["a", "b"],
                capture_traces=False,
                missing_ids=False,
            )
            == "trial"
        )


class TestAdapterEvaluatePassesExperimentType:
    def test_evaluate_threads_experiment_type_to_task_evaluator(
        self, monkeypatch
    ) -> None:
        """The classified type must reach task_evaluator.evaluate_with_result."""
        from opik_optimizer.algorithms.gepa_optimizer import adapter as adapter_module

        adapter = _make_adapter({"v1"})

        captured: dict[str, Any] = {}

        def fake_evaluate_with_result(**kwargs: Any) -> tuple[float, Any]:
            captured.update(kwargs)
            eval_result = MagicMock()
            eval_result.test_results = []
            return 1.0, eval_result

        monkeypatch.setattr(
            adapter_module.task_evaluator,
            "evaluate_with_result",
            fake_evaluate_with_result,
        )
        # Keep prompt rebuilding and config preparation out of the way.
        monkeypatch.setattr(
            adapter, "_rebuild_prompts_from_candidate", lambda candidate: {}
        )
        monkeypatch.setattr(
            adapter_module,
            "prepare_experiment_config",
            lambda **kwargs: {"project_name": "test"},
        )

        batch = [
            MagicMock(opik_item={"id": "t1", "question": "q", "answer": "a"}),
            MagicMock(opik_item={"id": "t2", "question": "q", "answer": "a"}),
        ]
        adapter.evaluate(batch, candidate={}, capture_traces=False)

        assert captured["experiment_type"] == "mini-batch"

    def test_evaluate_marks_full_valset_batch_as_trial(self, monkeypatch) -> None:
        from opik_optimizer.algorithms.gepa_optimizer import adapter as adapter_module

        adapter = _make_adapter({"v1", "v2"})

        captured: dict[str, Any] = {}

        def fake_evaluate_with_result(**kwargs: Any) -> tuple[float, Any]:
            captured.update(kwargs)
            eval_result = MagicMock()
            eval_result.test_results = []
            return 1.0, eval_result

        monkeypatch.setattr(
            adapter_module.task_evaluator,
            "evaluate_with_result",
            fake_evaluate_with_result,
        )
        monkeypatch.setattr(
            adapter, "_rebuild_prompts_from_candidate", lambda candidate: {}
        )
        monkeypatch.setattr(
            adapter_module,
            "prepare_experiment_config",
            lambda **kwargs: {"project_name": "test"},
        )

        batch = [
            MagicMock(opik_item={"id": "v1", "question": "q", "answer": "a"}),
            MagicMock(opik_item={"id": "v2", "question": "q", "answer": "a"}),
        ]
        adapter.evaluate(batch, candidate={}, capture_traces=False)

        assert captured["experiment_type"] == "trial"
