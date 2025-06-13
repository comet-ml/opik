from typing import Dict, Any

from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import OptimizableAgent, ChatPrompt, FewShotBayesianOptimizer
from opik_optimizer.datasets import hotpot_300


def levenshtein_ratio(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


class LiteLLMAgent(OptimizableAgent):
    model = "openai/gpt-4o-mini"
    project_name = "litellm-agent-wikipedia"


agent_config = {"chat-prompt": ChatPrompt(system="Answer as if you were a pirate.")}

# Test it out:
agent = LiteLLMAgent(agent_config)

result = agent.invoke_dataset_item(
    {"question": "Which is heavier: a baby whale or a baby elephant?"}, "question"
)

print(result)
# {'output': 'Arrr, matey! When it comes to weight, a baby whale be much heftier than a baby elephant! A newborn whale can weigh as much as 2,000 pounds or more, while a baby elephant usually tips the scales at around 200 to 300 pounds. So, if ye find yerself on the high seas with one o’ each, ye best be ready to hoist that whale, fer she be a mighty heavy treasure! Yarrr!'}


dataset = hotpot_300()

# Optimize it:
optimizer = FewShotBayesianOptimizer()
result = optimizer.optimize_agent(
    agent_class=LiteLLMAgent, dataset=dataset, metric=levenshtein_ratio
)
