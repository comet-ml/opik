from unittest.mock import MagicMock, patch
from types import SimpleNamespace

import pytest

pytest.importorskip("gepa")

from opik_optimizer_framework.optimizers.gepa_v2.gepa_adapter import (
    FrameworkGEPAAdapter,
    GEPAProgressCallback,
    _candidate_key,
    _extract_per_item_feedback,
    build_seed_candidate,
    rebuild_prompt_messages,
)
from opik_optimizer_framework.optimizers.gepa_v2.gepa_optimizer import GepaV2Optimizer
from opik_optimizer_framework.types import (
    OptimizationContext,
    OptimizationState,
    TrialResult,
)


# -- helpers ------------------------------------------------------------------


def _make_context(**overrides):
    prompt_messages = [
        {"role": "system", "content": "You are helpful."},
        {"role": "user", "content": "Answer: {question}"},
    ]
    defaults = dict(
        optimization_id="opt-gepa-test",
        dataset_name="test-dataset",
        prompt_messages=prompt_messages,
        model="openai/gpt-4o-mini",
        model_parameters={"temperature": 0.5},
        metric_type="equals",
        metric_parameters={},
        optimizer_type="GepaV2Optimizer",
        optimizer_parameters={
            "max_candidates": 3,
            "reflection_minibatch_size": 2,
            "seed": 42,
        },
        baseline_config={
            "prompt_messages": prompt_messages,
            "model": "openai/gpt-4o-mini",
            "model_parameters": {"temperature": 0.5},
        },
    )
    defaults.update(overrides)
    return OptimizationContext(**defaults)


def _make_trial(candidate_id, score, step_index=0):
    return TrialResult(
        candidate_id=candidate_id,
        step_index=step_index,
        score=score,
        metric_scores={"accuracy": score},
        experiment_id=f"exp-{candidate_id}",
        experiment_name=f"trial-{candidate_id}",
        prompt_messages=[{"role": "user", "content": f"prompt-{candidate_id}"}],
    )


def _make_raw_result(item_scores):
    """Build a mock EvaluationResult.

    item_scores: list of (item_id, assertions) where assertions is a list of
    (name, value, reason) tuples.
    """
    test_results = []
    for item_id, assertions in item_scores:
        score_results = []
        for name, value, reason in assertions:
            sr = SimpleNamespace(value=value, reason=reason, scoring_failed=False, name=name)
            score_results.append(sr)
        if not score_results:
            score_results.append(SimpleNamespace(value=0.0, reason=None, scoring_failed=False, name="metric"))
        tc = SimpleNamespace(
            dataset_item_id=item_id,
            task_output={"output": f"output-{item_id}"},
        )
        test_results.append(SimpleNamespace(test_case=tc, score_results=score_results, trial_id=0))
    return SimpleNamespace(test_results=test_results, experiment_id="exp-1", experiment_name="trial-1")


def _make_inst(item_id, question="Q"):
    return {"id": item_id, "question": question}


def _build_adapter(mock_eval_adapter):
    """Create a FrameworkGEPAAdapter with a mocked EvaluationAdapter."""
    return FrameworkGEPAAdapter(
        base_messages=[{"role": "user", "content": "Hi"}],
        baseline_config={"prompt_messages": [{"role": "user", "content": "Hi"}], "model": "test-model"},
        evaluation_adapter=mock_eval_adapter,
    )


# -- adapter helper tests -----------------------------------------------------


class TestBuildSeedCandidate:
    def test_builds_from_messages(self):
        messages = [
            {"role": "system", "content": "Be helpful."},
            {"role": "user", "content": "Hello {name}"},
        ]
        seed = build_seed_candidate(messages)
        assert seed == {"system_0": "Be helpful.", "user_1": "Hello {name}"}

    def test_uses_name_field_when_provided(self):
        messages = [
            {"role": "system", "content": "Be helpful.", "name": "instructions"},
            {"role": "user", "content": "Hello {name}"},
        ]
        seed = build_seed_candidate(messages)
        assert seed == {"instructions": "Be helpful.", "user_1": "Hello {name}"}

    def test_excludes_template_only_messages(self):
        messages = [
            {"role": "system", "content": "Be helpful."},
            {"role": "user", "content": "{question}"},
        ]
        seed = build_seed_candidate(messages)
        assert seed == {"system_0": "Be helpful."}
        assert "user_1" not in seed

    def test_keeps_messages_with_text_around_template(self):
        messages = [
            {"role": "system", "content": "Be helpful."},
            {"role": "user", "content": "Question: {question}\nContext: {context}"},
        ]
        seed = build_seed_candidate(messages)
        assert "user_1" in seed

    def test_empty_messages(self):
        assert build_seed_candidate([]) == {}


class TestRebuildPromptMessages:
    def test_uses_candidate_values(self):
        base = [
            {"role": "system", "content": "original"},
            {"role": "user", "content": "original user"},
        ]
        candidate = {"system_0": "improved", "user_1": "improved user"}
        result = rebuild_prompt_messages(base, candidate)
        assert result[0]["content"] == "improved"
        assert result[1]["content"] == "improved user"

    def test_falls_back_to_original(self):
        base = [
            {"role": "system", "content": "original"},
            {"role": "user", "content": "original user"},
        ]
        candidate = {"system_0": "improved"}
        result = rebuild_prompt_messages(base, candidate)
        assert result[0]["content"] == "improved"
        assert result[1]["content"] == "original user"

    def test_uses_name_field_when_provided(self):
        base = [
            {"role": "system", "content": "original", "name": "instructions"},
            {"role": "user", "content": "original user"},
        ]
        candidate = {"instructions": "improved", "user_1": "improved user"}
        result = rebuild_prompt_messages(base, candidate)
        assert result[0]["content"] == "improved"
        assert result[1]["content"] == "improved user"


class TestExtractPerItemFeedback:
    def test_extracts_scores_and_assertions(self):
        raw = _make_raw_result([
            ("item-1", [("relevance", 1.0, "The response is relevant")]),
            ("item-2", [
                ("relevance", 0.0, "The response is not relevant"),
                ("tone", 1.0, "Tone is appropriate"),
            ]),
        ])
        feedback = _extract_per_item_feedback(raw)
        assert "item-1" in feedback
        assert feedback["item-1"]["score"] == 1.0
        assert len(feedback["item-1"]["runs"]) == 1
        run0 = feedback["item-1"]["runs"][0]
        assert run0["assertions"][0]["name"] == "relevance"
        assert run0["assertions"][0]["value"] == 1.0
        assert run0["assertions"][0]["reason"] == "The response is relevant"
        # mean(0.0, 1.0) = 0.5
        assert feedback["item-2"]["score"] == 0.5
        assert len(feedback["item-2"]["runs"][0]["assertions"]) == 2

    def test_preserves_all_runs_per_item(self):
        raw = SimpleNamespace(test_results=[
            SimpleNamespace(
                test_case=SimpleNamespace(
                    dataset_item_id="item-1",
                    task_output={"output": "good run"},
                ),
                score_results=[
                    SimpleNamespace(name="a", value=1.0, reason="ok"),
                    SimpleNamespace(name="b", value=1.0, reason="ok"),
                ],
            ),
            SimpleNamespace(
                test_case=SimpleNamespace(
                    dataset_item_id="item-1",
                    task_output={"output": "bad run"},
                ),
                score_results=[
                    SimpleNamespace(name="a", value=0.0, reason="fail"),
                    SimpleNamespace(name="b", value=1.0, reason="ok"),
                ],
            ),
        ])
        feedback = _extract_per_item_feedback(raw)
        assert len(feedback["item-1"]["runs"]) == 2
        assert feedback["item-1"]["runs"][0]["output"] == "good run"
        assert feedback["item-1"]["runs"][1]["output"] == "bad run"
        # mean of run scores: (1.0 + 0.5) / 2 = 0.75
        assert feedback["item-1"]["score"] == 0.75

    def test_handles_none_result(self):
        assert _extract_per_item_feedback(None) == {}

    def test_handles_no_test_results(self):
        assert _extract_per_item_feedback(SimpleNamespace()) == {}


# -- adapter evaluate tests ---------------------------------------------------


class TestFrameworkGEPAAdapterEvaluate:
    @patch("gepa.core.adapter.EvaluationBatch")
    def test_evaluate_delegates_to_evaluation_adapter(self, mock_eb_cls):
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)

        mock_eval_adapter = MagicMock()
        trial = _make_trial("c-1", 0.75)
        raw_result = _make_raw_result([
            ("id-1", [("relevance", 1.0, "Good")]),
            ("id-2", [("relevance", 0.0, "Bad")]),
        ])
        mock_eval_adapter.evaluate_with_details.return_value = (trial, raw_result)

        adapter = _build_adapter(mock_eval_adapter)
        adapter._base_messages = [{"role": "user", "content": "Say {question}"}]

        insts = [
            {"id": "id-1", "question": "Q1"},
            {"id": "id-2", "question": "Q2"},
        ]

        result = adapter.evaluate(
            batch=insts,
            candidate={"user_0": "Say {question}"},
        )

        mock_eval_adapter.evaluate_with_details.assert_called_once()
        call_kwargs = mock_eval_adapter.evaluate_with_details.call_args
        assert call_kwargs.kwargs["dataset_item_ids"] == ["id-1", "id-2"]

        assert result.scores == [1.0, 0.0]
        assert result.outputs[0]["output"] == "output-id-1"
        assert result.outputs[1]["output"] == "output-id-2"

    @patch("gepa.core.adapter.EvaluationBatch")
    def test_evaluate_falls_back_to_trial_score(self, mock_eb_cls):
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)

        mock_eval_adapter = MagicMock()
        trial = _make_trial("c-1", 0.6)
        mock_eval_adapter.evaluate_with_details.return_value = (trial, None)

        adapter = _build_adapter(mock_eval_adapter)

        result = adapter.evaluate(batch=[_make_inst("id-1")], candidate={"user_0": "Hi"})
        assert result.scores == [0.6]

    @patch("gepa.core.adapter.EvaluationBatch")
    def test_evaluate_records_candidate_mapping(self, mock_eb_cls):
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)

        mock_eval_adapter = MagicMock()
        trial = _make_trial("c-123", 0.8)
        mock_eval_adapter.evaluate_with_details.return_value = (trial, None)

        adapter = _build_adapter(mock_eval_adapter)
        candidate = {"user_0": "Hello"}
        adapter.evaluate(
            batch=[_make_inst("x")],
            candidate=candidate,
        )

        key = _candidate_key(candidate)
        assert adapter._known_candidates[key] == "c-123"

    @patch("gepa.core.adapter.EvaluationBatch")
    def test_eval_purpose_initialization_before_iterations(self, mock_eb_cls):
        """Before any iteration starts (_current_step == -1), purpose is 'initialization'."""
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)
        mock_eval_adapter = MagicMock()
        trial = _make_trial("c-1", 0.9)
        mock_eval_adapter.evaluate_with_details.return_value = (trial, None)

        adapter = _build_adapter(mock_eval_adapter)
        assert adapter._current_step == -1

        adapter.evaluate(
            batch=[_make_inst("x")],
            candidate={"user_0": "Hi"},
        )

        call_kwargs = mock_eval_adapter.evaluate_with_details.call_args.kwargs
        assert call_kwargs["eval_purpose"] == "initialization"

    @patch("gepa.core.adapter.EvaluationBatch")
    def test_eval_purpose_exploration_minibatch(self, mock_eb_cls):
        """Minibatch reflection eval (capture_traces=True) → 'exploration:minibatch'."""
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)
        mock_eval_adapter = MagicMock()
        trial = _make_trial("c-1", 0.9)
        mock_eval_adapter.evaluate_with_details.return_value = (trial, None)

        adapter = _build_adapter(mock_eval_adapter)
        adapter._current_step = 2
        adapter._pending_eval_capture_traces = True
        adapter._pending_eval_parent_ids = []

        adapter.evaluate(
            batch=[_make_inst("x")],
            candidate={"user_0": "Hi"},
        )

        call_kwargs = mock_eval_adapter.evaluate_with_details.call_args.kwargs
        assert call_kwargs["eval_purpose"] == "exploration:minibatch"

    @patch("gepa.core.adapter.EvaluationBatch")
    def test_eval_purpose_exploration_mutation(self, mock_eb_cls):
        """Mutated candidate eval (capture_traces=False) → 'exploration:mutation'."""
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)
        mock_eval_adapter = MagicMock()
        trial = _make_trial("c-1", 0.9)
        mock_eval_adapter.evaluate_with_details.return_value = (trial, None)

        adapter = _build_adapter(mock_eval_adapter)
        adapter._current_step = 2
        adapter._pending_eval_capture_traces = False
        adapter._pending_eval_parent_ids = [0]

        adapter.evaluate(
            batch=[_make_inst("x")],
            candidate={"user_0": "Hi"},
        )

        call_kwargs = mock_eval_adapter.evaluate_with_details.call_args.kwargs
        assert call_kwargs["eval_purpose"] == "exploration:mutation"

    @patch("gepa.core.adapter.EvaluationBatch")
    def test_eval_purpose_validation_without_callback_metadata(self, mock_eb_cls):
        """During iteration without on_evaluation_start metadata, purpose is 'validation'."""
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)
        mock_eval_adapter = MagicMock()
        trial = _make_trial("c-1", 0.9)
        mock_eval_adapter.evaluate_with_details.return_value = (trial, None)

        adapter = _build_adapter(mock_eval_adapter)
        adapter._current_step = 3
        # Pre-register the candidate so it's "known"
        adapter._known_candidates[_candidate_key({"user_0": "Hi"})] = "c-prev"
        adapter._candidate_parents["c-prev"] = []

        adapter.evaluate(
            batch=[_make_inst("x")],
            candidate={"user_0": "Hi"},
        )

        call_kwargs = mock_eval_adapter.evaluate_with_details.call_args.kwargs
        assert call_kwargs["eval_purpose"] == "validation"


# -- adapter parent tracking tests -------------------------------------------


class TestAdapterParentTracking:
    @patch("gepa.core.adapter.EvaluationBatch")
    def test_selected_parent_used_for_new_candidate(self, mock_eb_cls):
        """on_candidate_selected sets the parent for new candidates in this iteration."""
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)

        mock_eval_adapter = MagicMock()
        parent_trial = _make_trial("parent-1", 0.8)
        child_trial = _make_trial("child-1", 0.9)
        mock_eval_adapter.evaluate_with_details.side_effect = [
            (parent_trial, None),
            (child_trial, None),
        ]

        adapter = _build_adapter(mock_eval_adapter)
        parent_candidate = {"user_0": "parent prompt"}
        child_candidate = {"user_0": "child prompt"}

        # Pre-populate: parent was evaluated in a previous step
        adapter._known_candidates[_candidate_key(parent_candidate)] = "parent-1"
        adapter._candidate_parents["parent-1"] = []
        adapter._gepa_idx_to_candidate_id[0] = "parent-1"

        adapter._on_new_step(1)
        adapter._on_candidate_selected(0)

        # First eval: known parent re-eval → persistent parents (empty list)
        adapter.evaluate(
            batch=[_make_inst("x")],
            candidate=parent_candidate,
        )
        first_call = mock_eval_adapter.evaluate_with_details.call_args_list[0]
        assert first_call.kwargs["parent_candidate_ids"] == []

        # Second eval: new child → should use selected parent
        adapter.evaluate(
            batch=[_make_inst("y")],
            candidate=child_candidate,
        )
        second_call = mock_eval_adapter.evaluate_with_details.call_args_list[1]
        assert second_call.kwargs["parent_candidate_ids"] == ["parent-1"]

    @patch("gepa.core.adapter.EvaluationBatch")
    def test_valset_evaluated_maps_gepa_idx(self, mock_eb_cls):
        """on_valset_evaluated maps GEPA candidate index to framework ID."""
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)

        mock_eval_adapter = MagicMock()
        trial = _make_trial("fw-123", 0.8)
        mock_eval_adapter.evaluate_with_details.return_value = (trial, None)

        adapter = _build_adapter(mock_eval_adapter)
        candidate = {"user_0": "test"}

        adapter.evaluate(
            batch=[_make_inst("x")],
            candidate=candidate,
        )

        adapter._on_valset_evaluated(0, candidate)

        assert adapter._gepa_idx_to_candidate_id[0] == "fw-123"

    @patch("gepa.core.adapter.EvaluationBatch")
    def test_merge_parent_ids_from_callback(self, mock_eb_cls):
        """MergeAcceptedEvent sets parent IDs for the next evaluate call."""
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)

        mock_eval_adapter = MagicMock()
        trial = _make_trial("merged-1", 0.95)
        mock_eval_adapter.evaluate_with_details.return_value = (trial, None)

        adapter = _build_adapter(mock_eval_adapter)
        adapter._current_step = 1
        adapter._gepa_idx_to_candidate_id = {0: "seed-id", 1: "parent-a", 2: "parent-b"}

        adapter._on_merge_accepted([1, 2])

        adapter.evaluate(
            batch=[_make_inst("z")],
            candidate={"user_0": "merged prompt"},
        )

        call_kwargs = mock_eval_adapter.evaluate_with_details.call_args.kwargs
        assert call_kwargs["parent_candidate_ids"] == ["parent-a", "parent-b"]

    def test_new_step_resets_state(self):
        """_on_new_step resets all per-iteration tracking state."""
        adapter = _build_adapter(MagicMock())
        adapter._selected_parent_id = "old-parent"
        adapter._pending_merge_parent_ids = [1]
        adapter._pending_eval_parent_ids = [0]
        adapter._pending_eval_capture_traces = True
        adapter._pending_eval_candidate_idx = 2

        adapter._on_new_step(5)

        assert adapter._current_step == 5
        assert adapter._selected_parent_id is None
        assert adapter._pending_merge_parent_ids is None
        assert adapter._pending_eval_parent_ids is None
        assert adapter._pending_eval_capture_traces is None
        assert adapter._pending_eval_candidate_idx is None

    @patch("gepa.core.adapter.EvaluationBatch")
    def test_candidate_id_reused_for_known_candidate(self, mock_eb_cls):
        """Known candidates get the same candidate_id passed to evaluate_with_details."""
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)

        mock_eval_adapter = MagicMock()
        trial = _make_trial("stable-id", 0.8)
        mock_eval_adapter.evaluate_with_details.return_value = (trial, None)

        adapter = _build_adapter(mock_eval_adapter)
        candidate = {"user_0": "same prompt"}

        adapter.evaluate(
            batch=[_make_inst("x")],
            candidate=candidate,
        )
        first_call = mock_eval_adapter.evaluate_with_details.call_args_list[0]
        assert first_call.kwargs["candidate_id"] is None

        adapter.evaluate(
            batch=[_make_inst("y")],
            candidate=candidate,
        )
        second_call = mock_eval_adapter.evaluate_with_details.call_args_list[1]
        assert second_call.kwargs["candidate_id"] == "stable-id"

    @patch("gepa.core.adapter.EvaluationBatch")
    def test_persistent_parents_for_derived_candidate(self, mock_eb_cls):
        """Derived candidates always carry parent_candidate_ids across re-evals."""
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)

        mock_eval_adapter = MagicMock()
        child_trial = _make_trial("child-1", 0.9)
        mock_eval_adapter.evaluate_with_details.return_value = (child_trial, None)

        adapter = _build_adapter(mock_eval_adapter)
        adapter._gepa_idx_to_candidate_id[0] = "parent-1"
        adapter._candidate_parents["parent-1"] = []
        adapter._known_candidates[_candidate_key({"user_0": "parent prompt"})] = "parent-1"

        child_candidate = {"user_0": "child prompt"}

        adapter._on_new_step(0)
        adapter._on_candidate_selected(0)
        adapter.evaluate(
            batch=[_make_inst("a")],
            candidate=child_candidate,
        )
        first_call = mock_eval_adapter.evaluate_with_details.call_args_list[0]
        assert first_call.kwargs["parent_candidate_ids"] == ["parent-1"]

        # Re-eval of child → should still have parent from _candidate_parents
        adapter._on_new_step(1)
        adapter.evaluate(
            batch=[_make_inst("b")],
            candidate=child_candidate,
        )
        second_call = mock_eval_adapter.evaluate_with_details.call_args_list[1]
        assert second_call.kwargs["parent_candidate_ids"] == ["parent-1"]

    @patch("gepa.core.adapter.EvaluationBatch")
    def test_batch_index_and_metadata_passed(self, mock_eb_cls):
        """batch_index, num_items, and capture_traces are passed to evaluate_with_details."""
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)

        mock_eval_adapter = MagicMock()
        trial = _make_trial("c-1", 0.75)
        mock_eval_adapter.evaluate_with_details.return_value = (trial, None)

        adapter = _build_adapter(mock_eval_adapter)
        adapter._on_new_step(3)

        batch = [_make_inst("id-1"), _make_inst("id-2")]
        adapter.evaluate(batch=batch, candidate={"user_0": "test"}, capture_traces=True)

        call_kwargs = mock_eval_adapter.evaluate_with_details.call_args.kwargs
        assert call_kwargs["batch_index"] == 3
        assert call_kwargs["num_items"] == 2
        assert call_kwargs["capture_traces"] is True

    @patch("gepa.core.adapter.EvaluationBatch")
    def test_evaluation_start_parent_ids_used(self, mock_eb_cls):
        """on_evaluation_start parent_ids (from GEPA) resolve to framework IDs."""
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)

        mock_eval_adapter = MagicMock()
        trial = _make_trial("new-child", 0.9)
        mock_eval_adapter.evaluate_with_details.return_value = (trial, None)

        adapter = _build_adapter(mock_eval_adapter)
        adapter._current_step = 1
        adapter._gepa_idx_to_candidate_id = {0: "seed-id", 1: "parent-fw-id"}

        adapter._on_evaluation_start(
            candidate_idx=None,
            parent_ids=[1],
            capture_traces=False,
        )

        adapter.evaluate(
            batch=[_make_inst("x")],
            candidate={"user_0": "new prompt"},
        )

        call_kwargs = mock_eval_adapter.evaluate_with_details.call_args.kwargs
        assert call_kwargs["parent_candidate_ids"] == ["parent-fw-id"]
        assert call_kwargs["capture_traces"] is False

    @patch("gepa.core.adapter.EvaluationBatch")
    def test_evaluation_start_capture_traces_overrides_arg(self, mock_eb_cls):
        """on_evaluation_start capture_traces overrides the evaluate() argument."""
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)

        mock_eval_adapter = MagicMock()
        trial = _make_trial("c-1", 0.8)
        mock_eval_adapter.evaluate_with_details.return_value = (trial, None)

        adapter = _build_adapter(mock_eval_adapter)
        adapter._on_new_step(1)

        adapter._on_evaluation_start(candidate_idx=0, parent_ids=[], capture_traces=True)

        # evaluate() arg says capture_traces=False — on_evaluation_start should win
        adapter.evaluate(
            batch=[_make_inst("x")],
            candidate={"user_0": "test"},
            capture_traces=False,
        )

        call_kwargs = mock_eval_adapter.evaluate_with_details.call_args.kwargs
        assert call_kwargs["capture_traces"] is True

    @patch("gepa.core.adapter.EvaluationBatch")
    def test_pending_eval_cleared_after_evaluate(self, mock_eb_cls):
        """Pending eval metadata is consumed (cleared) after evaluate()."""
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)

        mock_eval_adapter = MagicMock()
        mock_eval_adapter.evaluate_with_details.return_value = (_make_trial("c-1", 0.8), None)

        adapter = _build_adapter(mock_eval_adapter)
        adapter._current_step = 1
        adapter._on_evaluation_start(candidate_idx=0, parent_ids=[1], capture_traces=True)

        adapter.evaluate(
            batch=[_make_inst("x")],
            candidate={"user_0": "test"},
        )

        assert adapter._pending_eval_parent_ids is None
        assert adapter._pending_eval_capture_traces is None
        assert adapter._pending_eval_candidate_idx is None

    @patch("gepa.core.adapter.EvaluationBatch")
    def test_candidate_parents_stored_once(self, mock_eb_cls):
        """_candidate_parents is set on first eval and not overwritten by re-evals."""
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)

        mock_eval_adapter = MagicMock()
        trial = _make_trial("child-1", 0.85)
        mock_eval_adapter.evaluate_with_details.return_value = (trial, None)

        adapter = _build_adapter(mock_eval_adapter)
        adapter._current_step = 1
        adapter._gepa_idx_to_candidate_id[0] = "parent-1"
        adapter._selected_parent_id = "parent-1"

        child_candidate = {"user_0": "new child"}

        adapter.evaluate(
            batch=[_make_inst("x")],
            candidate=child_candidate,
        )
        assert adapter._candidate_parents["child-1"] == ["parent-1"]

        # Re-eval: _candidate_parents should NOT be overwritten
        adapter._selected_parent_id = "different-parent"
        adapter.evaluate(
            batch=[_make_inst("y")],
            candidate=child_candidate,
        )
        assert adapter._candidate_parents["child-1"] == ["parent-1"]


# -- progress callback tests --------------------------------------------------


class TestGEPAProgressCallback:
    def test_on_iteration_start_forwards_to_adapter(self):
        adapter = MagicMock()

        callback = GEPAProgressCallback(adapter=adapter)
        callback.on_iteration_start({"iteration": 3, "state": MagicMock()})

        adapter._on_new_step.assert_called_once_with(3)

    def test_on_candidate_selected_forwards_to_adapter(self):
        adapter = MagicMock()

        callback = GEPAProgressCallback(adapter=adapter)
        callback.on_candidate_selected({
            "iteration": 1,
            "candidate_idx": 2,
            "candidate": {"user_0": "test"},
            "score": 0.8,
        })

        adapter._on_candidate_selected.assert_called_once_with(2)

    def test_on_valset_evaluated_maps_idx_to_candidate(self):
        adapter = MagicMock()

        callback = GEPAProgressCallback(adapter=adapter)
        callback.on_valset_evaluated({
            "iteration": 1,
            "candidate_idx": 2,
            "candidate": {"user_0": "test"},
            "scores_by_val_id": {},
            "average_score": 0.8,
            "num_examples_evaluated": 5,
            "total_valset_size": 5,
            "parent_ids": [0, 1],
            "is_best_program": True,
            "outputs_by_val_id": None,
        })

        adapter._on_valset_evaluated.assert_called_once_with(2, {"user_0": "test"})

    def test_on_evaluation_start_forwards_to_adapter(self):
        adapter = MagicMock()

        callback = GEPAProgressCallback(adapter=adapter)
        callback.on_evaluation_start({
            "iteration": 2,
            "candidate_idx": 0,
            "batch_size": 3,
            "capture_traces": True,
            "parent_ids": [0],
            "inputs": [],
            "is_seed_candidate": False,
        })

        adapter._on_evaluation_start.assert_called_once_with(
            candidate_idx=0,
            parent_ids=[0],
            capture_traces=True,
        )

    def test_on_merge_accepted_forwards_parent_ids(self):
        adapter = MagicMock()

        callback = GEPAProgressCallback(adapter=adapter)
        callback.on_merge_accepted({
            "iteration": 5,
            "new_candidate_idx": 3,
            "parent_ids": [1, 2],
        })

        adapter._on_merge_accepted.assert_called_once_with([1, 2])


# -- adapter make_reflective_dataset tests ------------------------------------


class TestMakeReflectiveDataset:
    def test_shows_failed_and_passed_assertions_in_feedback(self):
        adapter = _build_adapter(MagicMock())

        eval_batch = SimpleNamespace(
            outputs=[{"output": "hello"}],
            scores=[0.0],
            trajectories=[
                {
                    "input": {"question": "Q1", "answer": "A1"},
                    "runs": [
                        {
                            "output": "hello",
                            "score": 0.0,
                            "assertions": [
                                {"name": "security concern", "value": 0.0, "reason": "The response does not address the security concern"},
                                {"name": "immediate steps", "value": 0.0, "reason": "No immediate steps to secure account were mentioned"},
                                {"name": "tone", "value": 1.0, "reason": "Tone is appropriate"},
                            ],
                        },
                    ],
                    "score": 0.0,
                }
            ],
        )

        result = adapter.make_reflective_dataset(
            candidate={"user_0": "Hi"},
            eval_batch=eval_batch,
            components_to_update=["user_0"],
        )

        assert "user_0" in result
        assert len(result["user_0"]) == 1
        feedback = result["user_0"][0]["Feedback"]
        assert "FAILED assertions" in feedback
        assert "security concern" in feedback
        assert "immediate steps" in feedback
        assert "PASSED assertions" in feedback
        assert "tone" in feedback

    def test_all_passed_feedback(self):
        adapter = _build_adapter(MagicMock())

        eval_batch = SimpleNamespace(
            outputs=[{"output": "hello"}],
            scores=[1.0],
            trajectories=[
                {
                    "input": {"question": "Q1"},
                    "runs": [
                        {
                            "output": "hello",
                            "score": 1.0,
                            "assertions": [
                                {"name": "relevance", "value": 1.0, "reason": "Relevant"},
                            ],
                        },
                    ],
                    "score": 1.0,
                }
            ],
        )

        result = adapter.make_reflective_dataset(
            candidate={"user_0": "Hi"},
            eval_batch=eval_batch,
            components_to_update=["user_0"],
        )

        feedback = result["user_0"][0]["Feedback"]
        assert "PASSED assertions" in feedback
        assert "FAILED" not in feedback

    def test_passes_dataset_item_fields_as_inputs(self):
        adapter = _build_adapter(MagicMock())

        eval_batch = SimpleNamespace(
            outputs=[{"output": "answer"}],
            scores=[0.5],
            trajectories=[
                {
                    "input": {"id": "1", "question": "What is X?", "context": "X is a thing", "extra": 42},
                    "runs": [{"output": "answer", "score": 0.5, "assertions": []}],
                    "score": 0.5,
                }
            ],
        )

        result = adapter.make_reflective_dataset(
            candidate={"user_0": "Hi"},
            eval_batch=eval_batch,
            components_to_update=["user_0"],
        )

        inputs = result["user_0"][0]["Inputs"]
        assert inputs["question"] == "What is X?"
        assert inputs["context"] == "X is a thing"
        assert inputs["extra"] == "42"
        assert "id" not in inputs

    def test_auto_detects_components(self):
        adapter = _build_adapter(MagicMock())

        eval_batch = SimpleNamespace(
            outputs=[{"output": "hello"}],
            scores=[0.5],
            trajectories=[
                {
                    "input": {"question": "Q1"},
                    "runs": [{"output": "hello", "score": 0.5, "assertions": []}],
                    "score": 0.5,
                }
            ],
        )

        result = adapter.make_reflective_dataset(
            candidate={"system_0": "Be helpful", "user_1": "Hi"},
            eval_batch=eval_batch,
            components_to_update=[],
        )

        assert "system_0" in result
        assert "user_1" in result

    def test_multiple_runs_shown_separately(self):
        adapter = _build_adapter(MagicMock())

        eval_batch = SimpleNamespace(
            outputs=[{"output": "run1"}],
            scores=[0.5],
            trajectories=[
                {
                    "input": {"question": "Q1"},
                    "runs": [
                        {
                            "output": "run1-output",
                            "score": 1.0,
                            "assertions": [{"name": "a", "value": 1.0, "reason": "ok"}],
                        },
                        {
                            "output": "run2-output",
                            "score": 0.0,
                            "assertions": [{"name": "a", "value": 0.0, "reason": "fail"}],
                        },
                    ],
                    "score": 0.5,
                }
            ],
        )

        result = adapter.make_reflective_dataset(
            candidate={"user_0": "Hi"},
            eval_batch=eval_batch,
            components_to_update=["user_0"],
        )

        records = result["user_0"]
        assert len(records) == 2
        # Sorted by num_failed desc — failed run first
        assert records[0]["Generated Outputs"] == "run2-output"
        assert "Run 2/2" in records[0]["Feedback"]
        assert "FAILED" in records[0]["Feedback"]
        assert records[1]["Generated Outputs"] == "run1-output"
        assert "Run 1/2" in records[1]["Feedback"]
        assert "PASSED" in records[1]["Feedback"]


# -- optimizer tests -----------------------------------------------------------


class TestGepaV2Optimizer:
    def test_run_calls_gepa_optimize(self):
        context = _make_context()
        state = OptimizationState()
        adapter = MagicMock()

        optimizer = GepaV2Optimizer()

        with patch("gepa.optimize") as mock_optimize:
            mock_optimize.return_value = SimpleNamespace(best_score=0.9, candidates=[])
            optimizer.run(
                context=context,
                training_set=[
                    {"id": "id-1", "question": "Q1", "answer": "A1"},
                    {"id": "id-2", "question": "Q2", "answer": "A2"},
                ],
                validation_set=[{"id": "id-3", "question": "Q3", "answer": "A3"}],
                evaluation_adapter=adapter,
                state=state,
            )

        mock_optimize.assert_called_once()
        call_kwargs = mock_optimize.call_args.kwargs
        assert call_kwargs["seed_candidate"] == {
            "system_0": "You are helpful.",
            "user_1": "Answer: {question}",
        }
        assert call_kwargs["reflection_lm"] == "openai/gpt-4o-mini"
        assert call_kwargs["seed"] == 42

    def test_callback_passed_to_optimize(self):
        context = _make_context()
        state = OptimizationState()
        adapter = MagicMock()

        optimizer = GepaV2Optimizer()

        with patch("gepa.optimize") as mock_optimize:
            mock_optimize.return_value = SimpleNamespace(best_score=0.9, candidates=[])
            optimizer.run(
                context=context,
                training_set=[{"id": "id-1", "question": "Q1", "answer": "A1"}],
                validation_set=[{"id": "id-2", "question": "Q2", "answer": "A2"}],
                evaluation_adapter=adapter,
                state=state,
            )

        call_kwargs = mock_optimize.call_args.kwargs
        callbacks = call_kwargs["callbacks"]
        assert isinstance(callbacks, list)
        assert len(callbacks) == 1
        assert isinstance(callbacks[0], GEPAProgressCallback)

    def test_stop_callbacks_passed(self):
        context = _make_context()
        state = OptimizationState()
        adapter = MagicMock()

        optimizer = GepaV2Optimizer()

        with patch("gepa.optimize") as mock_optimize:
            mock_optimize.return_value = SimpleNamespace(best_score=0.9, candidates=[])
            optimizer.run(
                context=context,
                training_set=[{"id": "id-1", "question": "Q1", "answer": "A1"}],
                validation_set=[{"id": "id-2", "question": "Q2", "answer": "A2"}],
                evaluation_adapter=adapter,
                state=state,
            )

        call_kwargs = mock_optimize.call_args.kwargs
        stop_cbs = call_kwargs["stop_callbacks"]
        assert isinstance(stop_cbs, list)
        assert len(stop_cbs) == 1
        assert callable(stop_cbs[0])

    def test_stop_callback_checks_trial_score(self):
        context = _make_context()
        state = OptimizationState()
        adapter = MagicMock()

        optimizer = GepaV2Optimizer()

        with patch("gepa.optimize") as mock_optimize:
            mock_optimize.return_value = SimpleNamespace(best_score=0.9, candidates=[])
            optimizer.run(
                context=context,
                training_set=[{"id": "id-1", "question": "Q1", "answer": "A1"}],
                validation_set=[{"id": "id-2", "question": "Q2", "answer": "A2"}],
                evaluation_adapter=adapter,
                state=state,
            )

        stopper = mock_optimize.call_args.kwargs["stop_callbacks"][0]
        # Before any full eval, score is 0 → don't stop
        assert stopper(MagicMock()) is False
        # Simulate a perfect trial score
        optimizer.adapter.best_full_eval_trial_score = 1.0
        assert stopper(MagicMock()) is True

    def test_adapter_receives_evaluation_adapter(self):
        context = _make_context()
        state = OptimizationState()
        adapter = MagicMock()

        optimizer = GepaV2Optimizer()

        with patch("gepa.optimize") as mock_optimize:
            mock_optimize.return_value = SimpleNamespace(best_score=0.9, candidates=[])
            optimizer.run(
                context=context,
                training_set=[{"id": "id-1", "question": "Q1", "answer": "A1"}],
                validation_set=[{"id": "id-2", "question": "Q2", "answer": "A2"}],
                evaluation_adapter=adapter,
                state=state,
            )

        gepa_adapter = mock_optimize.call_args.kwargs["adapter"]
        assert isinstance(gepa_adapter, FrameworkGEPAAdapter)
        assert gepa_adapter._evaluation_adapter is adapter

    def test_baseline_registered_when_provided(self):
        context = _make_context()
        state = OptimizationState()
        adapter = MagicMock()
        baseline = _make_trial("baseline-id", 0.7)

        optimizer = GepaV2Optimizer()

        with patch("gepa.optimize") as mock_optimize:
            mock_optimize.return_value = SimpleNamespace(best_score=0.9, candidates=[])
            optimizer.run(
                context=context,
                training_set=[{"id": "id-1", "question": "Q1", "answer": "A1"}],
                validation_set=[{"id": "id-2", "question": "Q2", "answer": "A2"}],
                evaluation_adapter=adapter,
                state=state,
                baseline_trial=baseline,
            )

        gepa_adapter = mock_optimize.call_args.kwargs["adapter"]
        assert gepa_adapter._baseline_candidate_id == "baseline-id"

    def test_gepa_not_installed_raises(self):
        import sys
        context = _make_context()
        state = OptimizationState()
        adapter = MagicMock()

        optimizer = GepaV2Optimizer()

        with patch.dict(sys.modules, {"gepa": None}):
            with pytest.raises(ImportError, match="gepa"):
                optimizer.run(
                    context=context,
                    training_set=[{"id": "id-1"}],
                    validation_set=[{"id": "id-2"}],
                    evaluation_adapter=adapter,
                    state=state,
                )

    def test_optimizer_parameters_forwarded(self):
        context = _make_context(
            optimizer_parameters={
                "max_candidates": 10,
                "reflection_minibatch_size": 5,
                "candidate_selection_strategy": "epsilon_greedy",
                "seed": 99,
            }
        )
        state = OptimizationState()
        adapter = MagicMock()

        optimizer = GepaV2Optimizer()

        with patch("gepa.optimize") as mock_optimize:
            mock_optimize.return_value = SimpleNamespace(best_score=0.9, candidates=[])
            optimizer.run(
                context=context,
                training_set=[{"id": "id-1", "question": "Q1", "answer": "A1"}],
                validation_set=[{"id": "id-2", "question": "Q2", "answer": "A2"}],
                evaluation_adapter=adapter,
                state=state,
            )

        call_kwargs = mock_optimize.call_args.kwargs
        assert call_kwargs["reflection_minibatch_size"] == 5
        assert call_kwargs["candidate_selection_strategy"] == "epsilon_greedy"
        assert call_kwargs["seed"] == 99
        # 10 max_candidates * 2 combined items * 5 multiplier
        assert call_kwargs["max_metric_calls"] == 100

    def test_no_split_same_dataset_for_train_and_val(self):
        context = _make_context()
        state = OptimizationState()
        adapter = MagicMock()

        optimizer = GepaV2Optimizer()

        train = [
            {"id": "id-1", "question": "Q1", "answer": "A1"},
            {"id": "id-2", "question": "Q2", "answer": "A2"},
            {"id": "id-3", "question": "Q3", "answer": "A3"},
        ]
        val = [{"id": "id-4", "question": "Q4", "answer": "A4"}]

        with patch("gepa.optimize") as mock_optimize:
            mock_optimize.return_value = SimpleNamespace(best_score=0.9, candidates=[])
            optimizer.run(
                context=context,
                training_set=train,
                validation_set=val,
                evaluation_adapter=adapter,
                state=state,
            )

        call_kwargs = mock_optimize.call_args.kwargs
        assert len(call_kwargs["trainset"]) == 4
        assert call_kwargs["trainset"] is call_kwargs["valset"]
        assert call_kwargs["trainset"][0]["question"] == "Q1"

    def test_no_split_deduplicates_by_id(self):
        context = _make_context()
        state = OptimizationState()
        adapter = MagicMock()

        optimizer = GepaV2Optimizer()

        # id-2 appears in both train and val
        train = [
            {"id": "id-1", "question": "Q1", "answer": "A1"},
            {"id": "id-2", "question": "Q2", "answer": "A2"},
        ]
        val = [
            {"id": "id-2", "question": "Q2", "answer": "A2"},
            {"id": "id-3", "question": "Q3", "answer": "A3"},
        ]

        with patch("gepa.optimize") as mock_optimize:
            mock_optimize.return_value = SimpleNamespace(best_score=0.9, candidates=[])
            optimizer.run(
                context=context,
                training_set=train,
                validation_set=val,
                evaluation_adapter=adapter,
                state=state,
            )

        call_kwargs = mock_optimize.call_args.kwargs
        assert len(call_kwargs["trainset"]) == 3

    def test_reflection_template_passed_to_adapter(self):
        from opik_optimizer_framework.optimizers.gepa_v2.gepa_optimizer import (
            GENERALIZATION_REFLECTION_TEMPLATE,
        )

        context = _make_context()
        state = OptimizationState()
        adapter = MagicMock()

        optimizer = GepaV2Optimizer()

        with patch("gepa.optimize") as mock_optimize:
            mock_optimize.return_value = SimpleNamespace(best_score=0.9, candidates=[])
            optimizer.run(
                context=context,
                training_set=[{"id": "id-1", "question": "Q1", "answer": "A1"}],
                validation_set=[],
                evaluation_adapter=adapter,
                state=state,
            )

        # Template passed as None to gepa.optimize (adapter handles it via propose_new_texts)
        call_kwargs = mock_optimize.call_args.kwargs
        assert call_kwargs["reflection_prompt_template"] is None

        # Template passed to the adapter instead
        gepa_adapter = optimizer.adapter
        assert gepa_adapter._reflection_prompt_template == GENERALIZATION_REFLECTION_TEMPLATE
        assert "<curr_param>" in gepa_adapter._reflection_prompt_template
        assert "<side_info>" in gepa_adapter._reflection_prompt_template
        assert "generalize" in gepa_adapter._reflection_prompt_template.lower()

    def test_cache_evaluation_disabled(self):
        context = _make_context()
        state = OptimizationState()
        adapter = MagicMock()

        optimizer = GepaV2Optimizer()

        with patch("gepa.optimize") as mock_optimize:
            mock_optimize.return_value = SimpleNamespace(best_score=0.9, candidates=[])
            optimizer.run(
                context=context,
                training_set=[{"id": "id-1", "question": "Q1", "answer": "A1"}],
                validation_set=[],
                evaluation_adapter=adapter,
                state=state,
            )

        call_kwargs = mock_optimize.call_args.kwargs
        assert call_kwargs["cache_evaluation"] is False


# -- registration test --------------------------------------------------------


class TestRegistration:
    def test_gepa_v2_optimizer_is_registered(self):
        from opik_optimizer_framework.optimizers.factory import create_optimizer
        optimizer = create_optimizer("GepaV2Optimizer")
        assert optimizer is not None
        assert isinstance(optimizer, GepaV2Optimizer)


# -- EvaluationAdapter caching tests ------------------------------------------


class TestEvaluationAdapterCache:
    def _make_adapter(self):
        from opik_optimizer_framework.evaluation_adapter import EvaluationAdapter

        mock_client = MagicMock()
        mock_emitter = MagicMock()
        state = OptimizationState()

        adapter = EvaluationAdapter(
            client=mock_client,
            dataset_name="test-ds",
            optimization_id="opt-1",
            metric_type="equals",
            metric_parameters={},
            state=state,
            event_emitter=mock_emitter,
        )
        return adapter, state

    @patch("opik_optimizer_framework.evaluation_adapter.validate_candidate")
    @patch("opik_optimizer_framework.evaluation_adapter.run_experiment_with_details")
    @patch("opik_optimizer_framework.evaluation_adapter.materialize_candidate")
    def test_cache_hit_skips_experiment(self, mock_materialize, mock_run_exp, mock_validate):
        mock_validate.return_value = (True, None)

        trial = _make_trial("c-1", 0.85)
        raw_result = _make_raw_result([("item-1", [("relevance", 1.0, "Good")])])
        mock_materialize.return_value = MagicMock(candidate_id="c-1")
        mock_run_exp.return_value = (trial, raw_result)

        adapter, state = self._make_adapter()
        config = {"prompt_messages": [{"role": "user", "content": "Hello"}]}
        item_ids = ["item-1", "item-2"]

        # First call: cache miss → runs experiment
        result1 = adapter.evaluate_with_details(config=config, dataset_item_ids=item_ids)
        assert mock_run_exp.call_count == 1
        assert result1[0].score == 0.85

        # Second call with same config+items: cache hit → no experiment
        result2 = adapter.evaluate_with_details(config=config, dataset_item_ids=item_ids)
        assert mock_run_exp.call_count == 1  # still 1
        assert result2[0].candidate_id == "c-1"
        assert result2[0].score == 0.85

    @patch("opik_optimizer_framework.evaluation_adapter.validate_candidate")
    @patch("opik_optimizer_framework.evaluation_adapter.run_experiment_with_details")
    @patch("opik_optimizer_framework.evaluation_adapter.materialize_candidate")
    def test_cache_miss_on_different_prompt(self, mock_materialize, mock_run_exp, mock_validate):
        mock_validate.return_value = (True, None)

        trial_a = _make_trial("c-a", 0.8)
        trial_b = _make_trial("c-b", 0.9)
        mock_materialize.return_value = MagicMock(candidate_id="c-a")
        mock_run_exp.side_effect = [(trial_a, None), (trial_b, None)]

        adapter, _ = self._make_adapter()
        item_ids = ["item-1"]

        config_a = {"prompt_messages": [{"role": "user", "content": "Hello A"}]}
        config_b = {"prompt_messages": [{"role": "user", "content": "Hello B"}]}

        adapter.evaluate_with_details(config=config_a, dataset_item_ids=item_ids)
        adapter.evaluate_with_details(config=config_b, dataset_item_ids=item_ids)
        assert mock_run_exp.call_count == 2

    @patch("opik_optimizer_framework.evaluation_adapter.validate_candidate")
    @patch("opik_optimizer_framework.evaluation_adapter.run_experiment_with_details")
    @patch("opik_optimizer_framework.evaluation_adapter.materialize_candidate")
    def test_cache_miss_on_different_items(self, mock_materialize, mock_run_exp, mock_validate):
        mock_validate.return_value = (True, None)

        trial_a = _make_trial("c-a", 0.8)
        trial_b = _make_trial("c-b", 0.9)
        mock_materialize.return_value = MagicMock(candidate_id="c-a")
        mock_run_exp.side_effect = [(trial_a, None), (trial_b, None)]

        adapter, _ = self._make_adapter()
        config = {"prompt_messages": [{"role": "user", "content": "Hello"}]}

        adapter.evaluate_with_details(config=config, dataset_item_ids=["item-1"])
        adapter.evaluate_with_details(config=config, dataset_item_ids=["item-1", "item-2"])
        assert mock_run_exp.call_count == 2

    @patch("opik_optimizer_framework.evaluation_adapter.validate_candidate")
    @patch("opik_optimizer_framework.evaluation_adapter.run_experiment_with_details")
    @patch("opik_optimizer_framework.evaluation_adapter.materialize_candidate")
    def test_cache_hit_still_records_in_state(self, mock_materialize, mock_run_exp, mock_validate):
        mock_validate.return_value = (True, None)

        trial = _make_trial("c-1", 0.85)
        mock_materialize.return_value = MagicMock(candidate_id="c-1")
        mock_run_exp.return_value = (trial, None)

        adapter, state = self._make_adapter()
        config = {"prompt_messages": [{"role": "user", "content": "Hello"}]}
        item_ids = ["item-1"]

        adapter.evaluate_with_details(config=config, dataset_item_ids=item_ids)
        adapter.evaluate_with_details(config=config, dataset_item_ids=item_ids)

        assert len(state.trials) == 2
        assert state.trials[0] is trial
        assert state.trials[1] is trial

    def test_cache_key_order_independent(self):
        from opik_optimizer_framework.evaluation_adapter import EvaluationAdapter

        config = {"prompt_messages": [{"role": "user", "content": "Hello"}]}
        key1 = EvaluationAdapter._make_cache_key(config, ["b", "a", "c"])
        key2 = EvaluationAdapter._make_cache_key(config, ["a", "c", "b"])
        assert key1 == key2
