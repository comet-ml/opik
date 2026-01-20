from opik_optimizer import ChatPrompt
from opik_optimizer.core.results import OptimizationResult
from opik_optimizer.utils.display.terminal import render_rich_result


def test_render_rich_result_returns_panel() -> None:
    prompt = ChatPrompt(system="Test", user="Query")
    result = OptimizationResult(
        optimizer="MetaPromptOptimizer",
        prompt=prompt,
        score=0.95,
        metric_name="f1_score",
        optimization_id="opt-123",
        dataset_id="ds-456",
        initial_prompt=prompt,
        initial_score=0.6,
        details={"trials_completed": 1, "model": "gpt-4"},
        history=[],
    )

    panel = render_rich_result(result)
    import rich

    assert isinstance(panel, rich.panel.Panel)

