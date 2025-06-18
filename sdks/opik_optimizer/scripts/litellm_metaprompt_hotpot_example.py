from typing import Any, Dict

from opik_optimizer import (
    ChatPrompt,
    MetaPromptOptimizer,
    AgentConfig,
    OptimizableAgent,
)
from opik_optimizer.datasets import hotpot_300

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult


dataset = hotpot_300()


def levenshtein_ratio(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


system_prompt = """
You are a helpful assistant. Use the `search_wikipedia` tool to find factual information when appropriate.
The user will provide a question string like "Who is Barack Obama?".
1. Extract the item to look up
2. Use the `search_wikipedia` tool to find details
3. Respond clearly to the user, stating the answer found by the tool.
"""

agent_config = AgentConfig(
    chat_prompt=ChatPrompt(system=system_prompt, user="{question}")
)


class LiteLLMAgent(OptimizableAgent):
    model = "openai/gpt-4o-mini"  # Using gpt-4o-mini for evaluation for speed
    project_name = "litellm-agent"


# Test it:
agent = LiteLLMAgent(agent_config)
result = agent.invoke_dataset_item(
    {"question": "Which is heavier: a newborn elephant, or a motor boat?"}
)
print(result)

# Optimize it:
optimizer = MetaPromptOptimizer(
    model="openai/gpt-4o-mini",  # Using gpt-4o-mini for evaluation for speed
    max_rounds=3,  # Number of optimization rounds
    num_prompts_per_round=4,  # Number of prompts to generate per round
    improvement_threshold=0.01,  # Minimum improvement required to continue
    temperature=0.1,  # Lower temperature for more focused responses
    max_completion_tokens=5000,  # Maximum tokens for model completion
    num_threads=1,  # Number of threads for parallel evaluation
    subsample_size=10,  # Fixed subsample size of 10 items
)
optimization_result = optimizer.optimize_agent(
    agent_class=LiteLLMAgent,
    agent_config=agent_config,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_samples=10,
)
optimization_result.display()
