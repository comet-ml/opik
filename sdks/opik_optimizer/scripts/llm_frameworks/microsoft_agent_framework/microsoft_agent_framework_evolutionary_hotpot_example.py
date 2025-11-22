"""
Microsoft Agent Framework + Opik Evolutionary Optimizer example.

Prerequisites:
    pip install --pre agent-framework opentelemetry-api opentelemetry-sdk opentelemetry-exporter-otlp

Telemetry configuration:
    export OTEL_EXPORTER_OTLP_ENDPOINT=https://www.comet.com/opik/api/v1/private/otel
    export OTEL_EXPORTER_OTLP_HEADERS='Authorization=<your-api-key>,Comet-Workspace=default,projectName=MAF-Demo'
    export ENABLE_OTEL=True
    export ENABLE_SENSITIVE_DATA=True
"""

from __future__ import annotations

import logging
from typing import Any

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult
from opik_optimizer import ChatPrompt, EvolutionaryOptimizer
from opik_optimizer.datasets import hotpot
from microsoft_agent_framework_agent import MicrosoftAgentFrameworkAgent

# Enable logging to see what's happening
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


dataset = hotpot(count=25)

prompt = ChatPrompt(
    system=(
        "You are a helpful assistant with access to a search_wikipedia tool. "
        "Always use the search_wikipedia tool to find accurate information before answering. "
        "Respond with one factual sentence and avoid extra commentary."
    ),
    user="{question}",
)

optimizer = EvolutionaryOptimizer(
    model="openai/gpt-4o-mini",
    enable_moo=False,
    enable_llm_crossover=True,
    infer_output_style=True,
    verbose=1,
    n_threads=2,
    population_size=5,
    num_generations=2,
)

optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    agent_class=MicrosoftAgentFrameworkAgent,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_samples=5,
)

optimization_result.display()
