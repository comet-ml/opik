from __future__ import annotations

import logging
import os
import time
from typing import Any

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult

from opik_optimizer import (
    ChatPrompt,
    EvolutionaryOptimizer,
    FewShotBayesianOptimizer,
    GepaOptimizer,
    HierarchicalReflectiveOptimizer,
    MetaPromptOptimizer,
    ParameterOptimizer,
)
from opik_optimizer.datasets.context7_eval import load_context7_dataset
from opik_optimizer.utils.toolcalling.optimizer_helpers import extract_tool_descriptions
from opik_optimizer.utils.toolcalling.normalize.tool_factory import (
    cursor_mcp_config_to_tools,
)
from opik_optimizer.utils.toolcalling.runtime import mcp_remote
from opik_optimizer.utils.toolcalling.runtime.mcp import ToolCallingDependencyError

logger = logging.getLogger(__name__)
if not logging.getLogger().handlers:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

CONTEXT7_URL = "https://mcp.context7.com/mcp"
MODEL = "openai/gpt-4o-mini"
SLEEP_BETWEEN_OPTIMIZERS_SECONDS = float(os.getenv("MCP_SMOKE_SLEEP_SECONDS", "12.0"))
api_key = os.getenv("CONTEXT7_API_KEY", "").strip()
headers = {"CONTEXT7_API_KEY": api_key} if api_key else {}

try:
    remote_tools = mcp_remote.list_tools_from_remote(url=CONTEXT7_URL, headers=headers)
except ToolCallingDependencyError as exc:
    raise RuntimeError(
        "MCP SDK is not installed. Install optional dependency `mcp` first."
    ) from exc

logger.info(
    "Discovered remote MCP tools: %s",
    [getattr(t, "name", "") for t in remote_tools[:10]],
)
logger.info("Sleep between optimizer runs: %.1fs", SLEEP_BETWEEN_OPTIMIZERS_SECONDS)

cursor_config = {
    "mcpServers": {
        "context7": {
            "url": CONTEXT7_URL,
            "headers": headers,
        }
    }
}
tools = cursor_mcp_config_to_tools(cursor_config)
dataset = load_context7_dataset(test_mode=True)
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
    base_score = scorer.score(
        reference=str(dataset_item.get("reference_answer", "")),
        output=llm_output,
    )
    return ScoreResult(
        name="context7_metric",
        value=float(base_score.value),
        reason="Levenshtein similarity to reference_answer.",
    )


def build_prompt() -> ChatPrompt:
    return ChatPrompt(
        system=(
            "You are a documentation assistant. Use MCP tools to resolve a library id "
            "and fetch docs before answering. Keep responses concise and grounded."
        ),
        user="{user_query}",
        tools=tools,
    )


optimizer_specs: list[tuple[str, Any, dict[str, Any], bool]] = [
    ("MetaPromptOptimizer", MetaPromptOptimizer, {"prompts_per_round": 2}, True),
    ("EvolutionaryOptimizer", EvolutionaryOptimizer, {"population_size": 4}, True),
    ("HierarchicalReflectiveOptimizer", HierarchicalReflectiveOptimizer, {}, True),
    ("GepaOptimizer", GepaOptimizer, {}, False),
    ("FewShotBayesianOptimizer", FewShotBayesianOptimizer, {}, False),
    ("ParameterOptimizer", ParameterOptimizer, {}, False),
]

for optimizer_name, optimizer_cls, extra_kwargs, supports_tool_opt in optimizer_specs:
    logger.info("===== %s =====", optimizer_name)
    prompt = build_prompt()
    before = extract_tool_descriptions(prompt)

    optimizer = optimizer_cls(
        model=MODEL,
        n_threads=1,
        verbose=0,
        **extra_kwargs,
    )

    optimize_tools_value: bool | None = True if supports_tool_opt else None

    if optimizer_name == "ParameterOptimizer":
        result = optimizer.optimize_parameter(
            prompt=prompt,
            dataset=dataset,
            metric=context7_metric,
            parameter_space={
                "temperature": {
                    "type": "float",
                    "min": 0.0,
                    "max": 0.4,
                }
            },
            max_trials=2,
            n_samples=2,
        )
    else:
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=context7_metric,
            max_trials=2,
            n_samples=2,
            allow_tool_use=True,
            optimize_tools=optimize_tools_value,
        )

    optimized_prompt = result.prompt
    if isinstance(optimized_prompt, dict):
        optimized_prompt = next(iter(optimized_prompt.values()))

    if optimized_prompt is None:
        raise RuntimeError(f"{optimizer_name}: no optimized prompt returned")

    after = extract_tool_descriptions(optimized_prompt)
    changed = sum(1 for name in after if after.get(name, "") != before.get(name, ""))
    logger.info(
        "%s score %.4f -> %.4f | tool_descriptions_changed=%s",
        optimizer_name,
        result.initial_score,
        result.score,
        changed,
    )
    time.sleep(SLEEP_BETWEEN_OPTIMIZERS_SECONDS)
