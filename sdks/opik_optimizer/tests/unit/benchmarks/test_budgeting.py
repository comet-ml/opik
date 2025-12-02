from benchmarks.utils.budgeting import derive_budgeted_optimize_params


def test_budgeting_prefers_train_rollout_budget() -> None:
    params = derive_budgeted_optimize_params("hotpot_train", "few_shot")
    # train_rollout_budget=737 with n_samples=100 caps trials to 7
    assert params == {"max_trials": 7}


def test_budgeting_falls_back_to_one_trial_when_budget_small() -> None:
    params = derive_budgeted_optimize_params("ifbench_train", "few_shot")
    assert params == {"max_trials": 1}


def test_budgeting_returns_none_when_no_budget_defined() -> None:
    params = derive_budgeted_optimize_params("hotpot_300", "few_shot")
    assert params is None
