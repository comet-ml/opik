from typing import Dict, Any

from opik_optimizer import EvolutionaryOptimizer

from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import OptimizableAgent, ChatPrompt, AgentConfig
from opik_optimizer.datasets import hotpot_300


dataset = hotpot_300()


def levenshtein_ratio(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


class LiteLLMAgent(OptimizableAgent):
    """Agent using LiteLLM for optimization."""

    model: str = "openai/gpt-4o-mini"
    project_name: str = "litellm-agent-wikipedia"
    input_dataset_field: str = "question"

    def init_agent(self, agent_config: AgentConfig) -> None:
        """Initialize the agent with the provided configuration."""
        self.agent_config = agent_config


prompt = """
You are a helpful assistant. Use the `search_wikipedia` tool to find factual information when appropriate.
The user will provide a question string like "Who is Barack Obama?".
1. Extract the item to look up
2. Use the `search_wikipedia` tool to find details
3. Respond clearly to the user, stating the answer found by the tool.
"""

agent_config = {"chat_prompt": ChatPrompt(system=prompt)}

# Test it:
agent = LiteLLMAgent(agent_config)
result = agent.invoke_dataset_item(
    {"question": "Which is heavier: a baby whale or a baby elephant?"}
)
print(result)

# Initialize the optimizer with custom parameters
optimizer = EvolutionaryOptimizer(
    model="gpt-4o-mini",
    population_size=10,
    num_generations=3,
    enable_moo=False,
    enable_llm_crossover=True,
    infer_output_style=True,
    verbose=1,
)

# Create the optimization configuration

# Optimize the prompt using the optimization config
result = optimizer.optimize_agent(
    agent_class=LiteLLMAgent,
    agent_config=agent_config,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_samples=10,
)

result.display()
