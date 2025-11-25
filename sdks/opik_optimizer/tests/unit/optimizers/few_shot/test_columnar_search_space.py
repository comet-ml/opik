import optuna

from opik_optimizer.algorithms.few_shot_bayesian_optimizer.few_shot_bayesian_optimizer import (
    ColumnarSearchSpace,
    FewShotBayesianOptimizer,
)


def _hotpot_like_records() -> list[dict[str, str]]:
    return [
        {
            "id": "1",
            "question": "Who wrote the book?",
            "answer": "Author A",
            "type": "bridge",
            "level": "easy",
        },
        {
            "id": "2",
            "question": "When was the treaty signed?",
            "answer": "Year B",
            "type": "bridge",
            "level": "easy",
        },
        {
            "id": "3",
            "question": "Which city hosts the event?",
            "answer": "City C",
            "type": "comparison",
            "level": "hard",
        },
        {
            "id": "4",
            "question": "What team won the match?",
            "answer": "Team D",
            "type": "bridge",
            "level": "hard",
        },
        {
            "id": "5",
            "question": "Where was the film shot?",
            "answer": "City E",
            "type": "bridge",
            "level": "hard",
        },
        {
            "id": "6",
            "question": "Who composed the anthem?",
            "answer": "Composer F",
            "type": "bridge",
            "level": "hard",
        },
    ]


def test_builds_columnar_search_space_with_repeated_categories() -> None:
    optimizer = FewShotBayesianOptimizer(model="openai/gpt-4o-mini")
    space = optimizer._build_columnar_search_space(_hotpot_like_records())

    assert space.is_enabled
    assert space.columns == ["type", "level"]
    assert space.max_group_size == 3
    assert set(space.combo_labels) == {
        "type=bridge|level=easy",
        "type=bridge|level=hard",
        "type=comparison|level=hard",
    }


def test_columnar_sampling_resolves_index_from_combo_and_member() -> None:
    optimizer = FewShotBayesianOptimizer(model="openai/gpt-4o-mini")
    records = _hotpot_like_records()
    space = optimizer._build_columnar_search_space(records)
    trial = optuna.trial.FixedTrial(
        {
            "example_0_combo": "type=bridge|level=easy",
            "example_0_member": 2,
        }
    )

    index, choice = optimizer._suggest_example_index(
        trial=trial,
        example_position=0,
        dataset_size=len(records),
        columnar_space=space,
        selected_indices=set(),
    )

    assert index == 0  # member index wraps across the two bridge/easy rows
    assert choice == {
        "combo": "type=bridge|level=easy",
        "member_index": 2,
        "resolved_index": 0,
        "diversity_adjusted": False,
    }


def test_fallback_to_index_sampling_when_no_columns_available() -> None:
    optimizer = FewShotBayesianOptimizer(model="openai/gpt-4o-mini")
    records = [
        {"id": "1", "question": "a?", "answer": "a"},
        {"id": "2", "question": "b?", "answer": "b"},
        {"id": "3", "question": "c?", "answer": "c"},
    ]
    space = optimizer._build_columnar_search_space(records)
    trial = optuna.trial.FixedTrial({"example_0": 2})

    index, choice = optimizer._suggest_example_index(
        trial=trial,
        example_position=0,
        dataset_size=len(records),
        columnar_space=space,
        selected_indices=set(),
    )

    assert not space.is_enabled
    assert index == 2
    assert choice is None


def test_columnar_selection_can_be_disabled() -> None:
    optimizer = FewShotBayesianOptimizer(
        model="openai/gpt-4o-mini", enable_columnar_selection=False
    )
    records = _hotpot_like_records()

    # Replicate the early selection path with columnar disabled.
    columnar_space = (
        optimizer._build_columnar_search_space(records)
        if optimizer.enable_columnar_selection
        else ColumnarSearchSpace.empty()
    )
    trial = optuna.trial.FixedTrial({"example_0": 1})

    index, choice = optimizer._suggest_example_index(
        trial=trial,
        example_position=0,
        dataset_size=len(records),
        columnar_space=columnar_space,
        selected_indices=set(),
    )

    assert not columnar_space.is_enabled
    assert index == 1
    assert choice is None


def test_diversity_adjustment_prefers_new_index_within_combo() -> None:
    optimizer = FewShotBayesianOptimizer(
        model="openai/gpt-4o-mini", enable_diversity=True
    )
    records = _hotpot_like_records()
    space = optimizer._build_columnar_search_space(records)
    selected_indices = {0}
    trial = optuna.trial.FixedTrial(
        {
            "example_0_combo": "type=bridge|level=easy",
            "example_0_member": 0,
        }
    )

    index, choice = optimizer._suggest_example_index(
        trial=trial,
        example_position=0,
        dataset_size=len(records),
        columnar_space=space,
        selected_indices=selected_indices,
    )

    assert index == 1  # shifted within combo to avoid duplicate
    assert choice and choice["diversity_adjusted"] is True


def test_diversity_can_be_disabled_to_allow_duplicates() -> None:
    optimizer = FewShotBayesianOptimizer(
        model="openai/gpt-4o-mini", enable_diversity=False
    )
    records = _hotpot_like_records()
    space = optimizer._build_columnar_search_space(records)
    selected_indices = {0}
    trial = optuna.trial.FixedTrial(
        {
            "example_0_combo": "type=bridge|level=easy",
            "example_0_member": 0,
        }
    )

    index, choice = optimizer._suggest_example_index(
        trial=trial,
        example_position=0,
        dataset_size=len(records),
        columnar_space=space,
        selected_indices=selected_indices,
    )

    assert index == 0  # no diversity shift
    assert choice and choice["diversity_adjusted"] is False
