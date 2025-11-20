"""
Requires `pip install ez-mcp-tools --upgrade`
"""

from typing import Any

from opik_optimizer import FewShotBayesianOptimizer

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult
from opik_optimizer.datasets import hotpot

from mcp_agent import create_mcp_prompt, MCPAgent

dataset = hotpot(count=300)

system_prompt = """Answer the question with a direct, accurate response.
You have access to a Wikipedia search tool - use it to find relevant information before answering.
Provide concise answers based on the search results."""


def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


# Configure the Comet ML MCP server:
mcp_server = {
    "name": "wikipedia-mcp",
    "command": "ez-mcp-server",
    "args": ["--quiet", "my_tools.py"],
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
