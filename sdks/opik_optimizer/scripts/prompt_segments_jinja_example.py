"""
Jinja + prompt segments (end-to-end example).

Problem statement:
We ship a banking support agent where a client (e.g., MCP) injects system/assistant
instructions we cannot edit. We need to update ONLY the user message to roll out
a new support flow: collect risk context, ask a clarifying question, and propose
next steps. This example demonstrates that flow end-to-end using an external
dataset and a mock MCP tool response (later replaceable with a real MCP call).

Why this example:
It shows how to keep system/assistant content stable, update just the user
segment, validate on a dataset, and optionally run an optimizer.

Workflow:
    ┌──────────────---┐
    │   Jinja inputs  │
    └──────-┬──────---┘
            │ render
            v
    ┌────────────────-┐
    │   ChatPrompt    │
    │ (system/user/   │
    │ assistant msgs) │
    └──────-┬──────---┘
            │ extract segments
            v
    ┌─────────────────┐
    │ segment & roles |
    └──────-┬──────---┘
            │ update user only
            v
    ┌────────────────┐
    │ updated prompt │
    └──────-┬──────---┘
            │ (optional)
            │ optimize_prompts="user"
            v
    ┌────────────────┐
    │ optimized user │
    │    message     │
    └────────────────┘
"""

from typing import Any, cast
import random

from jinja2 import Template  # type: ignore[import-not-found]
from datasets import load_dataset
from opik_optimizer import ChatPrompt, MetaPromptOptimizer
from opik_optimizer.api_objects.types import MetricFunction
from opik_optimizer.utils import prompt_segments

if __name__ == "__main__":
    """
    == 1. DATASET ==
    Source: Hugging Face `mlg-ulb/creditcard` (credit card fraud dataset).
    Why: Realistic transaction-like rows for dispute scenarios.
    How: We stratify by Class (fraud/legit) for balanced demo coverage and
    synthesize a dispute-style user query from Amount/Time/Class.
    """

    def load_creditcard_records(
        count: int = 10,
        seed: int = 7,
        fraud_ratio: float = 0.5,
    ) -> list[dict[str, Any]]:
        if load_dataset is None:
            raise SystemExit(
                "This example requires `datasets`. Install with `pip install datasets`."
            )
        dataset = load_dataset("mlg-ulb/creditcard", split="train")
        count = max(1, min(count, len(dataset)))
        return stratified_sample(
            dataset, count=count, seed=seed, fraud_ratio=fraud_ratio
        )

    def stratified_sample(
        dataset: Any, count: int, seed: int, fraud_ratio: float
    ) -> list[dict[str, Any]]:
        rng = random.Random(seed)
        fraud_indices = [i for i in range(len(dataset)) if dataset[i].get("Class") == 1]
        legit_indices = [i for i in range(len(dataset)) if dataset[i].get("Class") == 0]

        fraud_count = min(len(fraud_indices), max(1, int(count * fraud_ratio)))
        legit_count = min(len(legit_indices), max(1, count - fraud_count))

        rng.shuffle(fraud_indices)
        rng.shuffle(legit_indices)

        selected = fraud_indices[:fraud_count] + legit_indices[:legit_count]
        if len(selected) < count:
            remainder = [i for i in range(len(dataset)) if i not in set(selected)]
            rng.shuffle(remainder)
            selected += remainder[: max(0, count - len(selected))]

        rng.shuffle(selected)
        return [dataset[i] for i in selected]

    raw_records = load_creditcard_records(count=6, seed=23, fraud_ratio=0.5)

    """
    == 2. PROMPT TEMPLATE ==
    Jinja templates for system/assistant/user messages.
    The assistant message includes a mock MCP tool response that is later
    replaceable with a real MCP tool call.
    """
    system_template = "You are a helpful support assistant for {{ product_name }}."
    assistant_template = (
        "MCP tool instructions (client injected): {{ mcp_instructions }}\n"
        "{{ mcp_tool_response }}"
    )
    user_template = "User says: {{ user_query }}\nGoal: resolve the issue in one reply."

    prompt_template = ChatPrompt(
        system=system_template,
        messages=[
            {"role": "assistant", "content": assistant_template},
            {"role": "user", "content": user_template},
        ],
    )

    """
    == 3. MCP MOCK ==
    A lightweight stand-in for a future MCP tool call.
    It derives a compact risk summary from the same dataset row.
    """

    def mock_mcp_tool(record: dict[str, Any]) -> str:
        v_snippet = ", ".join(
            f"{record.get(f'V{i}'):.3f}"
            for i in range(1, 4)
            if record.get(f"V{i}") is not None
        )
        return (
            "MCP: "
            f"txn(amount={record.get('Amount')}, time={record.get('Time')}) | "
            f"risk_score={'high' if record.get('Class') == 1 else 'low'} | "
            f"features(V1..V3={v_snippet})"
        )

    """
    == 4. DATASET MATERIALIZATION ==
    Convert raw dataset rows into prompt-ready items by synthesizing
    dispute-style user queries and injecting the mock MCP response.
    """
    dataset = []
    for record in raw_records:
        amount = record.get("Amount")
        time = record.get("Time")
        label = record.get("Class")
        dispute_prefix = (
            "I don't recognize this charge"
            if label == 1
            else "I want to confirm a recent charge"
        )
        user_query = (
            f"{dispute_prefix}. Amount ${amount}. Timestamp {time}. Please advise."
        )
        dataset.append(
            {
                **record,
                "user_query": user_query,
                "product_name": "Northwind Bank",
                "mcp_instructions": "Follow compliance. Avoid asking for full SSN. Be concise.",
                "mcp_tool_response": mock_mcp_tool(record),
            }
        )

    sample = dataset[0]

    """
    == 5. RENDERING ==
    Render templates for a single sample so we can inspect segment ids.
    """

    def render_jinja_template(template: str, data: dict[str, Any]) -> str:
        return Template(template).render(**data)

    def render_prompt_from_template(
        prompt: ChatPrompt, data: dict[str, Any]
    ) -> ChatPrompt:
        system_template = prompt.system or ""
        message_templates = prompt.messages or []
        return ChatPrompt(
            system=render_jinja_template(system_template, data),
            messages=[
                {
                    "role": msg["role"],
                    "content": render_jinja_template(msg["content"], data),
                }
                for msg in message_templates
            ],
        )

    rendered_prompt = render_prompt_from_template(prompt_template, sample)

    print("Original messages (segment ids):")
    for segment in prompt_segments.extract_prompt_segments(rendered_prompt):
        if segment.kind == "message":
            print(f"- {segment.segment_id} | {segment.role} | {segment.content}")

    """
    == 6. SEGMENTS UPDATE ==
    Update ONLY the user segment with a new support flow.
    System + assistant (client-injected) content remains unchanged.
    """
    updates = {
        "message:1": (
            "User says: {{ user_query }}\n"
            "Ask 1 clarifying question about contact details (channel, date, call-back number).\n"
            "List 2 likely explanations for a term-deposit outreach.\n"
            "Propose next steps in bullet points."
        )
    }
    updated_prompt_template = prompt_segments.apply_segment_updates(
        prompt_template, updates
    )
    updated_rendered = render_prompt_from_template(updated_prompt_template, sample)

    print("Updated messages (segment ids):")
    for segment in prompt_segments.extract_prompt_segments(updated_rendered):
        if segment.kind == "message":
            print(f"- {segment.segment_id} | {segment.role} | {segment.content}")

    """
    == 7. VALIDATION ==
    Run a mock agent response over the dataset and score it with a
    simple heuristic metric (question + bullet list).
    """

    def mock_agent_response(_item: dict[str, Any]) -> str:
        return (
            "What channel contacted you, on what date, and do you have a call-back number?\n"
            "- Possible reason: scheduled outreach for term-deposit renewal\n"
            "- Possible reason: campaign follow-up for account promotion\n"
            "- Next step: verify the official number and avoid sharing sensitive info"
        )

    def metric(_item: dict[str, Any], output: str) -> float:
        has_question = "?" in output
        has_bullets = "-" in output or "*" in output
        return float(has_question and has_bullets)

    scores = [metric(item, mock_agent_response(item)) for item in dataset]
    print("Local validation avg score:", sum(scores) / max(len(scores), 1))

    """
    == 8. OPTIMIZATION ==
    Optionally call the optimizer using the dataset and metric.
    """

    result = optimizer.optimize_prompt(
        prompt=updated_prompt_template,
        dataset=dataset,
        metric=cast(MetricFunction, metric),
        optimize_prompts="user",
    )
    print("Optimization complete. Best score:", result.score)
