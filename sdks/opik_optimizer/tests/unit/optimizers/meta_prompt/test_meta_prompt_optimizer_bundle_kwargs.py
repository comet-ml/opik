from typing import Any

from opik_optimizer import ChatPrompt, MetaPromptOptimizer


class _DummyDataset:
    def __init__(self, items: list[dict[str, Any]]) -> None:
        self._items = items

    def get_items(self) -> list[dict[str, Any]]:
        return self._items


class _CapturingAgent:
    last_kwargs: dict[str, Any] | None = None

    def __init__(
        self,
        prompts: dict[str, Any],
        plan: list[str] | None = None,
        project_name: str | None = None,
        **kwargs: Any,
    ) -> None:
        self.kwargs = kwargs
        self.prompts = prompts
        self.plan = plan
        self.project_name = project_name
        _CapturingAgent.last_kwargs = kwargs

    def run_bundle(self, dataset_item: dict[str, Any]) -> dict[str, Any]:
        # return a predictable output to feed the metric
        return {"final_output": "ok", "trace": {"item": dataset_item}}


def test_bundle_agent_kwargs_passed_through() -> None:
    # Single prompt bundle; invoke is a no-op returning static text.
    p = ChatPrompt(user="{question}")
    p.invoke = lambda messages: "ok"  # type: ignore[assignment]

    dataset = _DummyDataset([{"question": "q1"}])
    optimizer = MetaPromptOptimizer(model="openai/gpt-4o-mini", n_threads=1)

    def metric(item: dict[str, Any], output: str, trace: dict | None = None) -> float:
        return 1.0

    score = optimizer._evaluate_bundle(
        bundle_prompts={"step": p},
        dataset=dataset,  # type: ignore[arg-type]
        metric=metric,
        n_samples=1,
        bundle_agent_class=_CapturingAgent,
        bundle_agent_kwargs={"search_fn": "dummy-search"},
    )

    # score should reflect the metric return value
    assert score == 1.0
    # ensure kwargs were plumbed to the agent
    assert _CapturingAgent.last_kwargs == {"search_fn": "dummy-search"}
