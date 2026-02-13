"""Example: optimize only user messages while keeping system/assistant fixed."""

from opik_optimizer import ChatPrompt, MetaPromptOptimizer
from opik_optimizer.datasets import context7_eval


def simple_metric(dataset_item: dict, output: str) -> float:
    reference = (dataset_item.get("reference_answer") or "").strip()
    if not reference:
        return 0.0
    return 1.0 if reference in output else 0.0


def main() -> None:
    dataset = context7_eval()
    prompt = ChatPrompt(
        system="You are a reliable assistant.",
        user="{user_query}",
        messages=[
            {
                "role": "assistant",
                "content": "MCP tool instructions injected here.",
            }
        ],
    )

    optimizer = MetaPromptOptimizer(model="openai/gpt-4o-mini", prompts_per_round=2)
    optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=simple_metric,
        optimize_prompts="user",  # keep system + assistant unchanged
    )


if __name__ == "__main__":
    main()
