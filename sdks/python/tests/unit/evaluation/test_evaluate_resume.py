"""
Unit tests for ``opik.evaluation.evaluator.evaluate_resume``.

We mock the resume context (built upstream by ``prepare_resume_context``) and
``_evaluate_task`` (the shared execution helper). What we verify is the glue
between them: which items get resolved, which get filtered as already-done,
which trial counts get propagated, and how scoring is wired.
"""

import logging
from unittest import mock

from opik.api_objects.dataset import dataset_item
from opik.evaluation import evaluation_result, evaluator, test_case, test_result
from opik.evaluation.metrics import score_result
from opik.evaluation.resume import context as resume_context


def _make_dataset(items):
    """Build a mock dataset/version whose stream returns ``items``."""
    dataset_ = mock.Mock()
    dataset_.__internal_api__stream_items_as_dataclasses__ = mock.MagicMock(
        return_value=iter(items)
    )
    return dataset_


def _make_context(
    *,
    items_to_stream,
    completed_runs_by_item_id=None,
    default_runs_per_item=1,
    dataset_filter_string=None,
    nb_samples=None,
    candidate_dataset_item_ids=None,
    experiment_project_name=None,
):
    experiment = mock.Mock()
    experiment.project_name = experiment_project_name
    return resume_context.ResumeContext(
        experiment=experiment,
        dataset=_make_dataset(items_to_stream),
        completed_runs_by_item_id=completed_runs_by_item_id or {},
        default_runs_per_item=default_runs_per_item,
        dataset_filter_string=dataset_filter_string,
        nb_samples=nb_samples,
        candidate_dataset_item_ids=candidate_dataset_item_ids,
    )


def _new_test_result(item_id: str, trace_id: str, score: float):
    """Build a TestResult mimicking one freshly produced by ``_evaluate_task``."""
    return test_result.TestResult(
        test_case=test_case.TestCase(
            trace_id=trace_id,
            dataset_item_id=item_id,
            task_output={"output": "x"},
            dataset_item_content={"id": item_id},
        ),
        score_results=[score_result.ScoreResult(name="equals_metric", value=score)],
        trial_id=0,
    )


def _previous_test_result(item_id: str, trace_id: str, score: float):
    """Build a TestResult mimicking one reconstructed from a prior run."""
    return _new_test_result(item_id, trace_id, score)


def _evaluation_result_from(test_results, experiment):
    return evaluation_result.EvaluationResult(
        dataset_id="dataset-id",
        experiment_id=experiment.id,
        experiment_name="exp-name",
        test_results=test_results,
        experiment_url="http://example/exp",
        trial_count=1,
        experiment_scores=[],
    )


class TestEvaluateResumeHappyFlow:
    def test_pending_items_executed_with_remaining_run_counts(self):
        items = [
            dataset_item.DatasetItem(id="done"),
            dataset_item.DatasetItem(id="partial"),
            dataset_item.DatasetItem(id="fresh"),
        ]
        context = _make_context(
            items_to_stream=items,
            completed_runs_by_item_id={"done": 3, "partial": 1},
            default_runs_per_item=3,
        )
        empty_new_result = _evaluation_result_from([], context.experiment)

        task = lambda data: {"output": "x"}
        with (
            mock.patch.object(
                evaluator.resume_module, "prepare_resume_context", return_value=context
            ),
            mock.patch.object(
                evaluator, "_evaluate_task", return_value=empty_new_result
            ) as mock_evaluate_task,
            mock.patch.object(
                evaluation_result.resume_merge,
                "reconstruct_previous_test_results",
                return_value=[],
            ),
        ):
            evaluator.evaluate_resume(
                "exp-1",
                task=task,
                scoring_key_mapping={"input": "user_question"},
            )

        call_kwargs = mock_evaluate_task.call_args.kwargs
        pending_ids = [item.id for item in call_kwargs["items"]]
        # done item filtered out; partial + fresh forwarded
        assert pending_ids == ["partial", "fresh"]
        # Both partial and fresh get the full trial count — partial items
        # have all 3 trials redone, not just the missing 2.
        runs = [item.execution_policy.runs_per_item for item in call_kwargs["items"]]
        assert runs == [3, 3]
        # context + user-supplied scoring_key_mapping wired through
        assert call_kwargs["experiment"] is context.experiment
        assert call_kwargs["dataset"] is context.dataset
        assert call_kwargs["trial_count"] == 3
        assert call_kwargs["scoring_key_mapping"] == {"input": "user_question"}
        assert call_kwargs["source"] == "experiment"

    def test_logs_info_and_calls_task_with_no_pending_items(self, capture_log):
        items = [dataset_item.DatasetItem(id="done")]
        context = _make_context(
            items_to_stream=items,
            completed_runs_by_item_id={"done": 1},
            default_runs_per_item=1,
        )
        empty_new_result = _evaluation_result_from([], context.experiment)

        with (
            mock.patch.object(
                evaluator.resume_module, "prepare_resume_context", return_value=context
            ),
            mock.patch.object(
                evaluator, "_evaluate_task", return_value=empty_new_result
            ) as mock_evaluate_task,
            mock.patch.object(
                evaluation_result.resume_merge,
                "reconstruct_previous_test_results",
                return_value=[],
            ),
        ):
            evaluator.evaluate_resume("exp-1", task=lambda _: {"output": "x"})

        call_kwargs = mock_evaluate_task.call_args.kwargs
        assert call_kwargs["items"] == []
        assert any(
            "already fully evaluated" in record.message
            and record.levelno == logging.INFO
            for record in capture_log.records
        )


class TestItemResolutionPathSelection:
    def test_candidate_ids_present__resolved_via_explicit_ids(self):
        items = [dataset_item.DatasetItem(id=f"ck-{i}") for i in range(3)]
        context = _make_context(
            items_to_stream=items,
            candidate_dataset_item_ids=["ck-0", "ck-1", "ck-2"],
            # filter + nb_samples must be ignored when checkpoint pins the set
            dataset_filter_string="tags contains 'ignored'",
            nb_samples=99,
        )

        with (
            mock.patch.object(
                evaluator.resume_module, "prepare_resume_context", return_value=context
            ),
            mock.patch.object(evaluator, "_evaluate_task"),
        ):
            evaluator.evaluate_resume("exp-1", task=lambda _: {"output": "x"})

        context.dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once_with(
            nb_samples=None,
            dataset_item_ids=["ck-0", "ck-1", "ck-2"],
            batch_size=mock.ANY,
            filter_string=None,
        )

    def test_no_checkpoint__resolved_via_filter_and_nb_samples(self):
        items = [dataset_item.DatasetItem(id="i-0")]
        context = _make_context(
            items_to_stream=items,
            candidate_dataset_item_ids=None,
            dataset_filter_string="tags contains 'eval'",
            nb_samples=10,
        )

        with (
            mock.patch.object(
                evaluator.resume_module, "prepare_resume_context", return_value=context
            ),
            mock.patch.object(evaluator, "_evaluate_task"),
        ):
            evaluator.evaluate_resume("exp-1", task=lambda _: {"output": "x"})

        context.dataset.__internal_api__stream_items_as_dataclasses__.assert_called_once_with(
            nb_samples=10,
            dataset_item_ids=None,
            batch_size=mock.ANY,
            filter_string="tags contains 'eval'",
        )


class TestMergeWithPreviouslyCompleted:
    def test_no_previous_items__returns_new_result_unchanged(self):
        context = _make_context(
            items_to_stream=[dataset_item.DatasetItem(id="fresh")],
            completed_runs_by_item_id={},  # no prior runs to merge
        )
        fresh_only = _new_test_result("fresh", "trace-fresh", score=1.0)
        new_result = _evaluation_result_from([fresh_only], context.experiment)

        with (
            mock.patch.object(
                evaluator.resume_module,
                "prepare_resume_context",
                return_value=context,
            ),
            mock.patch.object(evaluator, "_evaluate_task", return_value=new_result),
            mock.patch.object(
                evaluation_result.resume_merge, "reconstruct_previous_test_results"
            ) as mock_reconstruct,
        ):
            result = evaluator.evaluate_resume("exp-1", task=lambda _: {"output": "x"})

        # No reconstruction call when there's nothing to merge in.
        mock_reconstruct.assert_not_called()
        assert result is new_result

    def test_with_fully_completed_items__merges_into_returned_test_results(self):
        context = _make_context(
            items_to_stream=[
                dataset_item.DatasetItem(id="done"),
                dataset_item.DatasetItem(id="pending"),
            ],
            completed_runs_by_item_id={"done": 1, "pending": 0},
            default_runs_per_item=1,
        )
        pending_run_result = _new_test_result("pending", "trace-pending-new", score=1.0)
        new_result = _evaluation_result_from([pending_run_result], context.experiment)
        reconstructed = [
            _previous_test_result("done", "trace-done-old", score=1.0),
        ]

        with (
            mock.patch.object(
                evaluator.resume_module,
                "prepare_resume_context",
                return_value=context,
            ),
            mock.patch.object(evaluator, "_evaluate_task", return_value=new_result),
            mock.patch.object(
                evaluation_result.resume_merge,
                "reconstruct_previous_test_results",
                return_value=reconstructed,
            ) as mock_reconstruct,
        ):
            result = evaluator.evaluate_resume("exp-1", task=lambda _: {"output": "x"})

        # Reconstruction was asked for the fully-completed set ('done' only).
        mock_reconstruct.assert_called_once()
        assert mock_reconstruct.call_args.kwargs[
            "fully_completed_dataset_item_ids"
        ] == {"done"}

        # Result contains reconstructed-first, then new — both items present.
        trace_ids = [r.test_case.trace_id for r in result.test_results]
        assert trace_ids == ["trace-done-old", "trace-pending-new"]
        # Identity-preserved fields are reused from the slice result.
        assert result.experiment_id == new_result.experiment_id
        assert result.experiment_url == new_result.experiment_url

    def test_partial_items__not_reconstructed__redone_by_resume(self):
        """A partially-completed item is excluded from the reconstructed
        set — its trials are being redone end-to-end by the resume call."""
        context = _make_context(
            items_to_stream=[
                dataset_item.DatasetItem(id="done"),
                dataset_item.DatasetItem(id="partial"),
            ],
            # 'partial' has 1 of 3 trials done → partial, redo all
            completed_runs_by_item_id={"done": 3, "partial": 1},
            default_runs_per_item=3,
        )
        redone_results = [
            _new_test_result("partial", f"trace-partial-new-{i}", score=1.0)
            for i in range(3)
        ]
        new_result = _evaluation_result_from(redone_results, context.experiment)
        reconstructed_for_done = [
            _previous_test_result("done", f"trace-done-old-{i}", score=1.0)
            for i in range(3)
        ]

        with (
            mock.patch.object(
                evaluator.resume_module,
                "prepare_resume_context",
                return_value=context,
            ),
            mock.patch.object(evaluator, "_evaluate_task", return_value=new_result),
            mock.patch.object(
                evaluation_result.resume_merge,
                "reconstruct_previous_test_results",
                return_value=reconstructed_for_done,
            ) as mock_reconstruct,
        ):
            result = evaluator.evaluate_resume("exp-1", task=lambda _: {"output": "x"})

        # Only 'done' is in the fully-completed set passed to reconstruct;
        # 'partial' is excluded — it's being redone from scratch.
        assert mock_reconstruct.call_args.kwargs[
            "fully_completed_dataset_item_ids"
        ] == {"done"}

        # Final test_results: 3 reconstructed for 'done' + 3 fresh for 'partial'
        assert len(result.test_results) == 6
        assert (
            sum(1 for r in result.test_results if r.test_case.dataset_item_id == "done")
            == 3
        )
        assert (
            sum(
                1
                for r in result.test_results
                if r.test_case.dataset_item_id == "partial"
            )
            == 3
        )

    def test_experiment_scoring_functions__computed_over_merged_set(self):
        context = _make_context(
            items_to_stream=[
                dataset_item.DatasetItem(id="done"),
                dataset_item.DatasetItem(id="partial"),
            ],
            completed_runs_by_item_id={"done": 1, "partial": 0},
            default_runs_per_item=1,
        )
        new_result = _evaluation_result_from(
            [_new_test_result("partial", "trace-partial-new", score=1.0)],
            context.experiment,
        )
        reconstructed = [
            _previous_test_result("done", "trace-done-old", score=0.0),
        ]
        seen_test_results = []

        def mean_score(test_results):
            seen_test_results.extend(test_results)
            mean = sum(tr.score_results[0].value for tr in test_results) / len(
                test_results
            )
            return score_result.ScoreResult(name="mean_equals", value=mean)

        with (
            mock.patch.object(
                evaluator.resume_module,
                "prepare_resume_context",
                return_value=context,
            ),
            mock.patch.object(evaluator, "_evaluate_task", return_value=new_result),
            mock.patch.object(
                evaluation_result.resume_merge,
                "reconstruct_previous_test_results",
                return_value=reconstructed,
            ),
        ):
            result = evaluator.evaluate_resume(
                "exp-1",
                task=lambda _: {"output": "x"},
                experiment_scoring_functions=[mean_score],
            )

        # Aggregate saw both reconstructed and freshly-executed results.
        assert {tr.test_case.dataset_item_id for tr in seen_test_results} == {
            "done",
            "partial",
        }
        # Aggregate value reflects the merged set (mean of 1.0 and 0.0).
        assert len(result.experiment_scores) == 1
        assert result.experiment_scores[0].name == "mean_equals"
        assert result.experiment_scores[0].value == 0.5
        # Merged aggregates were logged to the backend on the experiment.
        context.experiment.log_experiment_scores.assert_called_once()
        logged_kwargs = context.experiment.log_experiment_scores.call_args.kwargs
        assert logged_kwargs["score_results"][0].name == "mean_equals"
        assert logged_kwargs["score_results"][0].value == 0.5
