from __future__ import annotations

import logging
import os
import time
import json
from typing import Any
from collections.abc import Mapping

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
OPTIMIZATION_ROUNDS = int(os.getenv("MCP_SMOKE_OPT_ROUNDS", "1"))
MAX_TRIALS = int(
    os.getenv("MCP_SMOKE_MAX_TRIALS", str(max(OPTIMIZATION_ROUNDS + 1, 2)))
)
N_SAMPLES = int(os.getenv("MCP_SMOKE_N_SAMPLES", "1"))
REQUIRE_TOOL_ATTEMPT = os.getenv("MCP_SMOKE_REQUIRE_TOOL_ATTEMPT", "1").strip() in {
    "1",
    "true",
    "True",
}
REQUIRE_PROMPT_CHANGE = os.getenv("MCP_SMOKE_REQUIRE_PROMPT_CHANGE", "1").strip() in {
    "1",
    "true",
    "True",
}
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
logger.info(
    "Smoke config: opt_rounds=%s max_trials=%s n_samples=%s require_tool_attempt=%s require_prompt_change=%s",
    OPTIMIZATION_ROUNDS,
    MAX_TRIALS,
    N_SAMPLES,
    REQUIRE_TOOL_ATTEMPT,
    REQUIRE_PROMPT_CHANGE,
)

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
dataset_items = list(dataset.get_items(nb_samples=1))
if not dataset_items:
    raise ValueError("Context7 dataset is empty; cannot run smoke test.")
logger.info("Using dataset sample size per optimization call: %s item(s)", N_SAMPLES)


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


def _prompt_payload(prompt: ChatPrompt) -> dict[str, Any]:
    """Return a deterministic payload for prompt-equality checks."""
    return prompt.to_dict()


def _prompts_equal(left: ChatPrompt, right: ChatPrompt) -> bool:
    """Return whether two prompts have equivalent serialized payloads."""
    left_payload = json.dumps(_prompt_payload(left), sort_keys=True, default=str)
    right_payload = json.dumps(_prompt_payload(right), sort_keys=True, default=str)
    return left_payload == right_payload


def _tool_description_map_from_tools_payload(tools_payload: Any) -> dict[str, str]:
    """Extract function-name -> description from serialized tools payload."""
    if not isinstance(tools_payload, list):
        return {}
    descriptions: dict[str, str] = {}
    for tool in tools_payload:
        if not isinstance(tool, Mapping):
            continue
        function = tool.get("function", {})
        if not isinstance(function, Mapping):
            continue
        name = function.get("name")
        if isinstance(name, str):
            descriptions[name] = str(function.get("description", ""))
    return descriptions


def _iter_tool_payloads(payload: Any) -> list[Any]:
    """Return all nested tools payloads found in a history candidate payload."""
    found: list[Any] = []
    stack: list[Any] = [payload]
    while stack:
        current = stack.pop()
        if isinstance(current, Mapping):
            if "tools" in current:
                found.append(current.get("tools"))
            for value in current.values():
                stack.append(value)
        elif isinstance(current, list):
            stack.extend(current)
    return found


def _history_contains_tool_description_attempt(
    history_entries: list[dict[str, Any]],
    baseline_descriptions: dict[str, str],
) -> bool:
    """Return True when any recorded candidate mutates tool descriptions."""
    for entry in history_entries:
        candidates = entry.get("candidates", [])
        if isinstance(candidates, list):
            for candidate in candidates:
                for tools_payload in _iter_tool_payloads(candidate):
                    candidate_descriptions = _tool_description_map_from_tools_payload(
                        tools_payload
                    )
                    if any(
                        candidate_descriptions.get(name, "")
                        != baseline_descriptions.get(name, "")
                        for name in candidate_descriptions
                    ):
                        return True
        trials = entry.get("trials", [])
        if isinstance(trials, list):
            for trial in trials:
                for tools_payload in _iter_tool_payloads(trial):
                    candidate_descriptions = _tool_description_map_from_tools_payload(
                        tools_payload
                    )
                    if any(
                        candidate_descriptions.get(name, "")
                        != baseline_descriptions.get(name, "")
                        for name in candidate_descriptions
                    ):
                        return True
    return False


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
    prompt_before = prompt.copy()
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
            max_trials=MAX_TRIALS,
            n_samples=N_SAMPLES,
        )
    else:
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=context7_metric,
            max_trials=MAX_TRIALS,
            n_samples=N_SAMPLES,
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
    prompt_changed = not _prompts_equal(prompt_before, optimized_prompt)
    history_entries = optimizer.get_history_entries()
    attempted_tool_change = _history_contains_tool_description_attempt(
        history_entries, before
    )
    logger.info(
        "%s score %.4f -> %.4f | prompt_changed=%s | tool_descriptions_changed=%s | tool_change_attempted=%s",
        optimizer_name,
        result.initial_score,
        result.score,
        prompt_changed,
        changed,
        attempted_tool_change,
    )
    if supports_tool_opt and REQUIRE_PROMPT_CHANGE and not prompt_changed:
        raise RuntimeError(
            f"{optimizer_name}: expected optimized prompt to change, but it did not."
        )
    if supports_tool_opt and REQUIRE_TOOL_ATTEMPT and not attempted_tool_change:
        raise RuntimeError(
            f"{optimizer_name}: expected tool-description mutation attempt, but none detected."
        )
    time.sleep(SLEEP_BETWEEN_OPTIMIZERS_SECONDS)
