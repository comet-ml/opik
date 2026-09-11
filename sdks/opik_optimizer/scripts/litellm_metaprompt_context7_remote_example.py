"""Remote Context7 MCP tool-optimization example with MetaPromptOptimizer."""

from __future__ import annotations

from difflib import SequenceMatcher
import logging
from typing import Any

from opik_optimizer import ChatPrompt, MetaPromptOptimizer
from opik_optimizer.datasets import context7_eval

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# CONTEXT7 REMOTE CONFIGURATION
# ---------------------------------------------------------------------------

CURSOR_MCP_CONFIG: dict[str, Any] = {
    "mcpServers": {
        "context7": {
            "url": "https://mcp.context7.com/mcp",
            # "headers": {"CONTEXT7_API_KEY": os.getenv("CONTEXT7_API_KEY", "")},
        }
    }
}

# ---------------------------------------------------------------------------
# DATASET + METRIC
# ---------------------------------------------------------------------------

dataset = context7_eval()


def context7_metric(dataset_item: dict[str, Any], llm_output: str) -> float:
    reference = (dataset_item.get("reference_answer") or "").strip()
    if not reference:
        return 0.0
    normalized_output = " ".join(str(llm_output or "").lower().split())
    ratio = SequenceMatcher(
        None,
        " ".join(reference.lower().split()),
        normalized_output,
    ).ratio()
    return ratio


# ---------------------------------------------------------------------------
# PROMPT + OPTIMIZATION
# ---------------------------------------------------------------------------

prompt = ChatPrompt(
    system="Use the docs tool when needed. Summarize sources with library IDs.",
    user="{user_query}",
    tools=CURSOR_MCP_CONFIG,
    model="openai/gpt-5-nano",
    model_parameters={"temperature": 0.2},
)

optimizer = MetaPromptOptimizer(
    model="openai/gpt-5-nano",
    prompts_per_round=3,
    n_threads=1,
    model_parameters={"temperature": 0.2},
)

result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=context7_metric,
    max_trials=6,
    n_samples=min(5, len(dataset.get_items())),
    optimize_prompts=False,
    optimize_tools=True,
)

if not result.prompt:
    raise RuntimeError("MetaPromptOptimizer did not return an optimized prompt.")

logger.info("Optimization complete! Best score=%s", result.score)

result.display()
