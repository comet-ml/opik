from typing import Any

import opik
from opik_optimizer import FewShotBayesianOptimizer

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult

from mcp_agent import create_mcp_prompt, MCPAgent

client = opik.Opik()
dataset = client.get_or_create_dataset("comet-mcp-tests-dataset")


def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


system_prompt = """
You are an expert AI assistant specializing in machine learning experiment analysis using Comet ML.

You help researchers analyze their experiments from named projects in the default workspace, providing insights, recommendations, and answering questions about their ML experiments.

Always provide actionable insights and concrete recommendations for improving experiment results.
"""

# Configure the Comet ML MCP server:
mcp_server = {
    "name": "comet-mcp",
    "command": "comet-mcp",
    "args": [],
    "env": {},
}
prompt = create_mcp_prompt(
    mcp_server=mcp_server,
    system_prompt=system_prompt,
    user_template="{question}",
    model="openai/gpt-4o-mini",
)

# Optimize it:
optimizer = FewShotBayesianOptimizer(
    model="openai/gpt-4o-mini",
    min_examples=3,
    max_examples=8,
    n_threads=4,
    seed=42,
)

optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    agent_class=MCPAgent,
    dataset=dataset,
    metric=levenshtein_ratio,
    max_trials=5,
)

optimization_result.display()
