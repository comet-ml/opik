from opik.simulation.episode import (
    EpisodeAssertion,
    EpisodeBudgets,
    EpisodeBudgetMetric,
    EpisodeResult,
    build_trajectory_summary,
    make_max_turns_assertion,
    make_tool_call_budget,
)


def test_episode_result__dict_input__uses_fallbacks__happyflow():
    episode = EpisodeResult.from_any(
        {"assertions": [{"name": "schema", "passed": True}]},
        fallback_scenario_id="fallback-scenario",
        fallback_thread_id="thread-123",
    )

    assert episode is not None
    assert episode.scenario_id == "fallback-scenario"
    assert episode.thread_id == "thread-123"
    assert episode.assertions[0].name == "schema"
    assert episode.assertions[0].passed is True


def test_episode_result__unsupported_type_input__returns_none__happyflow():
    episode = EpisodeResult.from_any(
        42, fallback_scenario_id="fallback-scenario", fallback_thread_id="thread-123"
    )
    assert episode is None


def test_episode_result__is_passing__checks_assertions_and_budgets__happyflow():
    passing_episode = EpisodeResult(
        scenario_id="scenario-ok",
        assertions=[
            EpisodeAssertion(name="hard-check", passed=True, severity="error"),
            EpisodeAssertion(name="soft-check", passed=False, severity="warning"),
        ],
        budgets=EpisodeBudgets(
            max_turns=EpisodeBudgetMetric(used=4, limit=5, unit="count"),
            tool_calls=EpisodeBudgetMetric(used=2, limit=2, unit="count"),
        ),
    )
    assert passing_episode.is_passing() is True

    failing_episode = EpisodeResult(
        scenario_id="scenario-fail",
        assertions=[
            EpisodeAssertion(name="hard-check", passed=False, severity="error")
        ],
    )
    assert failing_episode.is_passing() is False


def test_build_trajectory_summary__with_tool_budget__happyflow():
    trajectory = [
        {"action": "search_docs", "observation": "ok"},
        {"observation": "no action"},
        {"action": "call_tool"},
    ]

    summary = build_trajectory_summary(trajectory)
    budget = make_tool_call_budget(trajectory, limit=2)

    assert summary == {"steps_count": 3, "tool_calls_count": 2}
    assert budget.used == 2
    assert budget.limit == 2
    assert budget.passed is True


def test_make_max_turns_assertion__within_limit__happyflow():
    conversation_history = [
        {"role": "user", "content": "hello"},
        {"role": "assistant", "content": "hi"},
        {"role": "user", "content": "need refund"},
        {"role": "assistant", "content": "sure"},
    ]

    assertion = make_max_turns_assertion(conversation_history, max_turns=2)
    assert assertion.passed is True
    assert assertion.reason == "turns_used=2, limit=2"
