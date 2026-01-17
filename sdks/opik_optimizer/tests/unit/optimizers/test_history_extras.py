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
    state.end_round(round_handle=handle, best_score=0.5)
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
    state.end_round(round_handle=handle, best_score=0.2)
    entry = state.get_entries()[0]
    assert entry["stop_reason"] == "max_trials"
    assert entry["stopped"] is True


def test_default_dataset_split_used_when_not_passed() -> None:
    state = OptimizationHistoryState()
    state.set_default_dataset_split("train")
    handle = state.start_round(round_index=0)
    state.record_trial(round_handle=handle, score=0.3, candidate={"prompt": "x"})
    state.end_round(round_handle=handle, best_score=0.3)
    entries = state.get_entries()
    assert entries[0]["trials"][0]["dataset_split"] == "train"


def test_selection_meta_and_pareto_defaults() -> None:
    state = OptimizationHistoryState()
    state.set_default_dataset_split("train")
    handle = state.start_round(round_index=0)
    state.set_selection_meta({"policy": "test"})
    state.set_pareto_front([{"id": "c1", "score": 0.5}])
    candidate = {"prompt": "x"}
    state.record_trial(
        round_handle=handle,
        score=0.4,
        candidate=candidate,
        candidate_id_prefix="auto",
    )
    state.end_round(round_handle=handle, best_score=0.5)

    entry = state.get_entries()[0]
    assert entry["extra"]["selection_meta"]["policy"] == "test"
    assert entry["extra"]["pareto_front"][0]["id"] == "c1"
    assert entry.get("dataset_split") == "train"
    assert entry["trials"][0].get("candidate_id", "").startswith("auto_")
    assert "id" not in candidate
