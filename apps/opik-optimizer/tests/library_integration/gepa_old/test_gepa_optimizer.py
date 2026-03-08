from unittest.mock import MagicMock, patch
from types import SimpleNamespace

import pytest

pytest.importorskip("gepa")

from opik_optimizer_framework.optimizers.gepa_old.gepa_adapter import (
    FrameworkGEPAAdapter,
    GEPAProgressCallback,
    OpikDataInst,
    _candidate_key,
    _extract_per_item_feedback,
    build_data_insts,
    build_seed_candidate,
    infer_dataset_keys,
    rebuild_prompt_messages,
)
from opik_optimizer_framework.optimizers.gepa_old.gepa_optimizer import GepaLegacyOptimizer
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
        model="openai/gpt-4o-mini",
        metric_type="equals",
        optimizer_type="GepaLegacyOptimizer",
        optimizer_parameters={
            "max_candidates": 3,
            "reflection_minibatch_size": 2,
            "seed": 42,
        },
        optimizable_keys=["prompt_messages"],
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
        config={"prompt_messages": [{"role": "user", "content": f"prompt-{candidate_id}"}]},
    )


def _make_raw_result(item_scores):
    """Build a fake raw EvaluationResult with per-item test_results."""
    test_results = []
    for item_id, score, reasons in item_scores:
        score_results = []
        for reason in reasons:
            sr = SimpleNamespace(value=score, reason=reason, scoring_failed=False, name="assertion")
            score_results.append(sr)
        if not score_results:
            score_results.append(SimpleNamespace(value=score, reason=None, scoring_failed=False, name="metric"))
        tc = SimpleNamespace(
            dataset_item_id=item_id,
            task_output={"output": f"output-{item_id}"},
        )
        test_results.append(SimpleNamespace(test_case=tc, score_results=score_results, trial_id=0))
    return SimpleNamespace(test_results=test_results, experiment_id="exp-1", experiment_name="trial-1")


def _build_adapter(mock_eval_adapter):
    """Create a FrameworkGEPAAdapter with mocked internals, bypassing __init__."""
    adapter = FrameworkGEPAAdapter.__new__(FrameworkGEPAAdapter)
    adapter._base_messages = [{"role": "user", "content": "Hi"}]
    adapter._baseline_config = {"prompt_messages": [{"role": "user", "content": "Hi"}], "model": "test-model"}
    adapter._evaluation_adapter = mock_eval_adapter
    adapter._last_per_item_feedback = {}
    adapter._gepa_idx_to_candidate_id = {}
    adapter._known_candidates = {}
    adapter._candidate_parents = {}
    adapter._current_step = -1
    adapter._baseline_candidate_id = None
    adapter._seed_candidate_key = None
    adapter._selected_parent_id = None
    adapter._pending_merge_parent_ids = None
    adapter._pending_eval_parent_ids = None
    adapter._pending_eval_capture_traces = None
    adapter._pending_eval_candidate_idx = None
    return adapter


# -- adapter helper tests -----------------------------------------------------


class TestBuildSeedCandidate:
    def test_builds_from_messages(self):
        messages = [
            {"role": "system", "content": "Be helpful."},
            {"role": "user", "content": "Hello {name}"},
        ]
        seed = build_seed_candidate(messages)
        assert seed == {"system_0": "Be helpful.", "user_1": "Hello {name}"}

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


class TestInferDatasetKeys:
    def test_infers_question_answer(self):
        items = [{"question": "What?", "answer": "42", "id": "1"}]
        input_key, output_key = infer_dataset_keys(items)
        assert output_key == "answer"
        assert input_key == "question"

    def test_defaults_on_empty(self):
        assert infer_dataset_keys([]) == ("text", "label")


class TestBuildDataInsts:
    def test_builds_instances(self):
        items = [
            {"question": "Q1", "answer": "A1", "context": "ctx", "id": "1"},
            {"question": "Q2", "answer": "A2", "id": "2"},
        ]
        insts = build_data_insts(items, "question", "answer")
        assert len(insts) == 2
        assert insts[0].input_text == "Q1"
        assert insts[0].answer == "A1"
        assert insts[0].additional_context == {"context": "ctx"}
        assert insts[1].additional_context == {}


class TestExtractPerItemFeedback:
    def test_extracts_scores_and_reasons(self):
        raw = _make_raw_result([
            ("item-1", 1.0, ["The response is relevant"]),
            ("item-2", 0.0, ["The response is not relevant"]),
        ])
        feedback = _extract_per_item_feedback(raw)
        assert "item-1" in feedback
        assert feedback["item-1"]["score"] == 1.0
        assert feedback["item-1"]["reasons"] == ["The response is relevant"]
        assert feedback["item-2"]["score"] == 0.0

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
        raw_result = _make_raw_result([("id-1", 1.0, ["Good"]), ("id-2", 0.0, ["Bad"])])
        mock_eval_adapter.evaluate_with_details.return_value = (trial, raw_result)

        adapter = _build_adapter(mock_eval_adapter)
        adapter._base_messages = [{"role": "user", "content": "Say {question}"}]

        insts = [
            OpikDataInst(input_text="Q1", answer="A1", additional_context={}, opik_item={"id": "id-1", "question": "Q1"}),
            OpikDataInst(input_text="Q2", answer="A2", additional_context={}, opik_item={"id": "id-2", "question": "Q2"}),
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
        """When raw_result has no per-item data, fall back to the trial's aggregate score."""
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)

        mock_eval_adapter = MagicMock()
        trial = _make_trial("c-1", 0.6)
        mock_eval_adapter.evaluate_with_details.return_value = (trial, None)

        adapter = _build_adapter(mock_eval_adapter)

        insts = [
            OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "id-1"}),
        ]

        result = adapter.evaluate(batch=insts, candidate={"user_0": "Hi"})

        assert result.scores == [0.6]

    @patch("gepa.core.adapter.EvaluationBatch")
    def test_evaluate_records_candidate_mapping(self, mock_eb_cls):
        """After evaluate(), the candidate is recorded for lineage tracking."""
        mock_eb_cls.side_effect = lambda **kwargs: SimpleNamespace(**kwargs)

        mock_eval_adapter = MagicMock()
        trial = _make_trial("c-123", 0.8)
        mock_eval_adapter.evaluate_with_details.return_value = (trial, None)

        adapter = _build_adapter(mock_eval_adapter)
        candidate = {"user_0": "Hello"}
        adapter.evaluate(
            batch=[OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "x"})],
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
            batch=[OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "x"})],
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
            batch=[OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "x"})],
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
            batch=[OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "x"})],
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
        # No pending_eval_* set — simulates valset eval via engine.evaluator

        adapter.evaluate(
            batch=[OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "x"})],
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

        # Pre-populate: parent was evaluated in a previous step (no parents itself)
        adapter._known_candidates[_candidate_key(parent_candidate)] = "parent-1"
        adapter._candidate_parents["parent-1"] = []
        adapter._gepa_idx_to_candidate_id[0] = "parent-1"

        adapter._on_new_step(1)
        # Simulate on_candidate_selected callback (fires before evaluation)
        adapter._on_candidate_selected(0)

        # First eval: known parent re-eval → persistent parents (empty list)
        adapter.evaluate(
            batch=[OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "x"})],
            candidate=parent_candidate,
        )
        first_call = mock_eval_adapter.evaluate_with_details.call_args_list[0]
        assert first_call.kwargs["parent_candidate_ids"] == []

        # Second eval: new child → should use selected parent
        adapter.evaluate(
            batch=[OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "y"})],
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

        # Evaluate the candidate → registers in _known_candidates
        adapter.evaluate(
            batch=[OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "x"})],
            candidate=candidate,
        )

        # Simulate on_valset_evaluated callback (fires after evaluation)
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
        adapter._gepa_idx_to_candidate_id = {0: "seed-id", 1: "parent-a", 2: "parent-b"}

        # Callback sets merge parents
        adapter._on_merge_accepted([1, 2])

        # Next evaluate should use those parent IDs
        adapter.evaluate(
            batch=[OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "z"})],
            candidate={"user_0": "merged prompt"},
        )

        call_kwargs = mock_eval_adapter.evaluate_with_details.call_args.kwargs
        assert call_kwargs["parent_candidate_ids"] == ["parent-a", "parent-b"]

    def test_new_step_resets_state(self):
        """_on_new_step resets all per-iteration tracking state."""
        adapter = _build_adapter(MagicMock())
        adapter._selected_parent_id = "old-parent"
        adapter._pending_merge_parent_ids = ["merge-1"]
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

        # First eval: new candidate, no existing ID
        adapter.evaluate(
            batch=[OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "x"})],
            candidate=candidate,
        )
        first_call = mock_eval_adapter.evaluate_with_details.call_args_list[0]
        assert first_call.kwargs["candidate_id"] is None

        # Second eval: known candidate, should reuse ID
        adapter.evaluate(
            batch=[OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "y"})],
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

        # First eval: new child with parent
        adapter._on_new_step(0)
        adapter._on_candidate_selected(0)
        adapter.evaluate(
            batch=[OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "a"})],
            candidate=child_candidate,
        )
        first_call = mock_eval_adapter.evaluate_with_details.call_args_list[0]
        assert first_call.kwargs["parent_candidate_ids"] == ["parent-1"]

        # Second eval: re-eval of child → should still have parent from _candidate_parents
        adapter._on_new_step(1)
        adapter.evaluate(
            batch=[OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "b"})],
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

        batch = [
            OpikDataInst(input_text="Q1", answer="A1", additional_context={}, opik_item={"id": "id-1"}),
            OpikDataInst(input_text="Q2", answer="A2", additional_context={}, opik_item={"id": "id-2"}),
        ]
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
        adapter._gepa_idx_to_candidate_id = {0: "seed-id", 1: "parent-fw-id"}

        # Simulate the GEPA callback flow: on_evaluation_start fires before evaluate()
        adapter._on_evaluation_start(
            candidate_idx=None,  # new candidate, not yet in state
            parent_ids=[1],      # GEPA parent index
            capture_traces=False,
        )

        adapter.evaluate(
            batch=[OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "x"})],
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

        # on_evaluation_start says capture_traces=True
        adapter._on_evaluation_start(candidate_idx=0, parent_ids=[], capture_traces=True)

        # evaluate() arg says capture_traces=False — on_evaluation_start should win
        adapter.evaluate(
            batch=[OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "x"})],
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
        adapter._on_evaluation_start(candidate_idx=0, parent_ids=[1], capture_traces=True)

        adapter.evaluate(
            batch=[OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "x"})],
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
        adapter._gepa_idx_to_candidate_id[0] = "parent-1"
        adapter._selected_parent_id = "parent-1"

        child_candidate = {"user_0": "new child"}

        # First eval: new candidate, parent set
        adapter.evaluate(
            batch=[OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "x"})],
            candidate=child_candidate,
        )
        assert adapter._candidate_parents["child-1"] == ["parent-1"]

        # Re-eval: _candidate_parents should NOT be overwritten
        adapter._selected_parent_id = "different-parent"
        adapter.evaluate(
            batch=[OpikDataInst(input_text="", answer="", additional_context={}, opik_item={"id": "y"})],
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
    @patch("gepa.core.adapter.EvaluationBatch")
    def test_uses_reasons_from_evaluation_suite(self, mock_eb_cls):
        adapter = _build_adapter(MagicMock())

        eval_batch = SimpleNamespace(
            outputs=[{"output": "hello"}],
            scores=[0.0],
            trajectories=[
                {
                    "input": {"question": "Q1", "answer": "A1"},
                    "output": "hello",
                    "score": 0.0,
                    "reasons": [
                        "The response does not address the security concern",
                        "No immediate steps to secure account were mentioned",
                    ],
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
        assert "Evaluator feedback:" in feedback
        assert "security concern" in feedback

    @patch("gepa.core.adapter.EvaluationBatch")
    def test_falls_back_to_expected_answer_when_no_reasons(self, mock_eb_cls):
        adapter = _build_adapter(MagicMock())

        eval_batch = SimpleNamespace(
            outputs=[{"output": "hello"}],
            scores=[0.5],
            trajectories=[
                {
                    "input": {"question": "Q1", "answer": "expected answer"},
                    "output": "hello",
                    "score": 0.5,
                    "reasons": [],
                }
            ],
        )

        result = adapter.make_reflective_dataset(
            candidate={"user_0": "Hi"},
            eval_batch=eval_batch,
            components_to_update=["user_0"],
        )

        feedback = result["user_0"][0]["Feedback"]
        assert "Expected answer: expected answer" in feedback


# -- optimizer tests -----------------------------------------------------------


class TestGepaLegacyOptimizer:
    def test_run_calls_gepa_optimize(self):
        context = _make_context()
        state = OptimizationState()

        call_count = {"n": 0}

        def mock_evaluate_with_details(config, dataset_item_ids, parent_candidate_ids=None):
            call_count["n"] += 1
            trial = _make_trial(f"c-{call_count['n']}", 0.7)
            state.trials.append(trial)
            if state.best_trial is None or trial.score > state.best_trial.score:
                state.best_trial = trial
            raw = _make_raw_result([(iid, 0.7, ["Good"]) for iid in dataset_item_ids])
            return trial, raw

        adapter = MagicMock()
        adapter.evaluate_with_details = MagicMock(side_effect=mock_evaluate_with_details)

        optimizer = GepaLegacyOptimizer()

        with patch("gepa.optimize") as mock_optimize:
            mock_optimize.return_value = SimpleNamespace(candidates=[])
            optimizer.run(
                context=context,
                training_set=[{"id": "id-1", "question": "Q1", "answer": "A1"}, {"id": "id-2", "question": "Q2", "answer": "A2"}],
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

        optimizer = GepaLegacyOptimizer()

        with patch("gepa.optimize") as mock_optimize:
            mock_optimize.return_value = SimpleNamespace(candidates=[])
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

    def test_adapter_receives_evaluation_adapter(self):
        context = _make_context()
        state = OptimizationState()
        adapter = MagicMock()

        optimizer = GepaLegacyOptimizer()

        with patch("gepa.optimize") as mock_optimize:
            mock_optimize.return_value = SimpleNamespace(candidates=[])
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

    def test_gepa_not_installed_raises(self):
        import sys
        context = _make_context()
        state = OptimizationState()
        adapter = MagicMock()

        optimizer = GepaLegacyOptimizer()

        with patch.dict(sys.modules, {"gepa": None}):
            with pytest.raises(ImportError, match="gepa"):
                optimizer.run(
                    context=context,
                    training_set=[{"id": "id-1"}],
                    validation_set=[{"id": "id-2"}],
                    evaluation_adapter=adapter,
                    state=state,
                )

    def test_train_val_items_passed_with_full_data(self):
        context = _make_context()
        state = OptimizationState()
        adapter = MagicMock()

        optimizer = GepaLegacyOptimizer()

        train = [
            {"id": "id-1", "question": "Q1", "answer": "A1"},
            {"id": "id-2", "question": "Q2", "answer": "A2"},
            {"id": "id-3", "question": "Q3", "answer": "A3"},
        ]
        val = [{"id": "id-4", "question": "Q4", "answer": "A4"}]

        with patch("gepa.optimize") as mock_optimize:
            mock_optimize.return_value = SimpleNamespace(candidates=[])
            optimizer.run(
                context=context,
                training_set=train,
                validation_set=val,
                evaluation_adapter=adapter,
                state=state,
            )

        call_kwargs = mock_optimize.call_args.kwargs
        assert len(call_kwargs["trainset"]) == 3
        assert len(call_kwargs["valset"]) == 1
        assert call_kwargs["trainset"][0].opik_item == train[0]
        assert call_kwargs["trainset"][0].input_text == "Q1"
        assert call_kwargs["trainset"][0].answer == "A1"

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

        optimizer = GepaLegacyOptimizer()

        with patch("gepa.optimize") as mock_optimize:
            mock_optimize.return_value = SimpleNamespace(candidates=[])
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
        assert call_kwargs["max_metric_calls"] == 10


class TestRegistration:
    def test_gepa_optimizer_is_registered(self):
        from opik_optimizer_framework.optimizers.factory import create_optimizer
        optimizer = create_optimizer("GepaLegacyOptimizer")
        assert optimizer is not None
