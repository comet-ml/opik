from opik_optimizer.optimization_result import OptimizationHistoryState


def test_dataset_split_propagates_into_history() -> None:
    state = OptimizationHistoryState()
    handle = state.start_round(round_index=0)
    state.record_trial(
        round_handle=handle,
        score=0.5,
        candidate={"prompt": "x"},
        trial_index=0,
        dataset_split="validation",
    )
    entries = state.get_entries()
    assert entries[0]["trials"][0]["dataset_split"] == "validation"


def test_pareto_and_selection_meta_recorded() -> None:
    state = OptimizationHistoryState()
    handle = state.start_round(round_index=0)
    state.record_trial(round_handle=handle, score=0.1, candidate={"prompt": "x"})
    state.end_round(
        round_handle=handle,
        best_score=0.1,
        pareto_front=[{"score": 0.1, "id": "p1"}],
        selection_meta={"selection_policy": "test_policy"},
    )
    entry = state.get_entries()[0]
    assert entry["extra"].get("pareto_front") == [{"score": 0.1, "id": "p1"}]
    assert entry["extra"].get("selection_meta") == {"selection_policy": "test_policy"}


def test_stop_flags_on_trial() -> None:
    state = OptimizationHistoryState()
    handle = state.start_round(round_index=0)
    state.record_trial(
        round_handle=handle,
        score=0.2,
        candidate={"prompt": "x"},
        trial_index=0,
        stop_reason="max_trials",
    )
    entry = state.get_entries()[0]
    assert entry["stop_reason"] == "max_trials"
    assert entry["stopped"] is True
