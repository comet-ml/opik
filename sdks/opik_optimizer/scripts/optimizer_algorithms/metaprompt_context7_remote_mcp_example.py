from __future__ import annotations

import logging
import os
from typing import Any

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult

from opik_optimizer import ChatPrompt, MetaPromptOptimizer
from opik_optimizer.datasets.context7_eval import load_context7_dataset
from opik_optimizer.utils.toolcalling.core.metadata import extract_tool_descriptions
from opik_optimizer.utils.toolcalling.runtime import mcp_remote
from opik_optimizer.utils.toolcalling.runtime.mcp import ToolCallingDependencyError

logger = logging.getLogger(__name__)
if not logging.getLogger().handlers:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

CONTEXT7_URL = "https://mcp.context7.com/mcp"
ALLOWED_TOOLS = ["resolve-library-id", "query-docs"]

# Remote MCP works without API key for listing tools. Add key if available.
context7_api_key = os.getenv("CONTEXT7_API_KEY", "").strip()
headers = {"CONTEXT7_API_KEY": context7_api_key} if context7_api_key else {}

try:
    tools_live = mcp_remote.list_tools_from_remote(url=CONTEXT7_URL, headers=headers)
    logger.info(
        "Discovered remote MCP tools: %s",
        [getattr(t, "name", "") for t in tools_live[:10]],
    )
except ToolCallingDependencyError as exc:
    raise RuntimeError(
        "MCP SDK is not installed. Install optional dependency `mcp` first."
    ) from exc

# ---------------------------------------------------------------------------
# Cursor-style MCP config (active path)
# ---------------------------------------------------------------------------
cursor_config = {
    "mcpServers": {
        "context7": {
            "url": CONTEXT7_URL,
            "headers": headers,
        }
    }
}
# Default behavior: when `allowed_tools` is omitted, the SDK resolves all tools
# exposed by the MCP server.
# To constrain tools, use the OpenAI-style MCP block shown below with `allowed_tools`.

# ---------------------------------------------------------------------------
# OpenAI-style MCP tool entry (alternative)
# ---------------------------------------------------------------------------
# tools = [
#     {
#         "type": "mcp",
#         "server_label": "context7",
#         "server_url": CONTEXT7_URL,
#         "headers": headers,
#         "allowed_tools": ALLOWED_TOOLS,
#     }
# ]

dataset = load_context7_dataset(test_mode=False)
scorer = LevenshteinRatio()


def context7_metric(dataset_item: dict[str, Any], llm_output: str) -> Any:
    """Compute Levenshtein similarity between reference_answer and model output.

    Args:
        dataset_item: Dataset row dict with optional ``reference_answer`` text.
        llm_output: Model output text to compare.

    Returns:
        ScoreResult with ``name='context7_metric'``, ``value`` as a float similarity
        score, and a human-readable reason.

    Notes:
        Missing/None ``reference_answer`` values are treated as ``""``, so the score
        compares output against an empty string in that edge case.
    """
    reference_text = dataset_item.get("reference_answer") or ""
    base_score = scorer.score(
        reference=str(reference_text),
        output=llm_output,
    )
    return ScoreResult(
        name="context7_metric",
        value=float(base_score.value),
        reason="Levenshtein similarity to reference_answer.",
    )


prompt = ChatPrompt(
    system=(
        "You are a documentation assistant. Use MCP tools to resolve a library id "
        "and fetch docs before answering. Keep responses concise and grounded."
    ),
    user="{user_query}",
    tools=cursor_config,
)
before_descriptions = extract_tool_descriptions(prompt)
logger.info("Dataset size: %s", len(dataset.get_items()))
logger.info("Initial tool descriptions:")
for name, description in sorted(before_descriptions.items()):
    logger.debug("- %s: %s", name, description[:140])

optimizer = MetaPromptOptimizer(
    model="openai/gpt-5-nano",
    prompts_per_round=4,
    n_threads=1,
    verbose=1,
    model_parameters={
        "temperature": 0.2,
    },
)

optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=context7_metric,
    max_trials=6,
    n_samples=3,
    allow_tool_use=True,
    optimize_tools=True,
)
optimization_result.display()

optimized_prompt = optimization_result.prompt
if isinstance(optimized_prompt, dict):
    optimized_prompt = next(iter(optimized_prompt.values()))

if optimized_prompt is None:
    raise RuntimeError("No optimized prompt returned.")

after_descriptions = extract_tool_descriptions(optimized_prompt)
logger.info("Tool description changes:")
changed = 0
for name in sorted(after_descriptions):
    before = before_descriptions.get(name, "")
    after = after_descriptions[name]
    if before != after:
        changed += 1
        logger.info("- CHANGED %s", name)
        logger.debug("  before: %s", before[:180])
        logger.debug("  after:  %s", after[:180])
if changed == 0:
    logger.info("- No signature description changes detected.")
