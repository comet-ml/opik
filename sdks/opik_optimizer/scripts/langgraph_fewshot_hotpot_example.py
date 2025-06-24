from typing import Any, Dict

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult

from opik_optimizer.datasets import hotpot_300

from opik_optimizer import (
    ChatPrompt,
    FewShotBayesianOptimizer,
)

from langgraph_agent import LangGraphAgent


def levenshtein_ratio(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    """
    Calculate the Levenshtein ratio score between dataset answer and LLM output.
    """
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


dataset = hotpot_300()

prompt_template = """Answer the following questions as best you can. You have access to the following tools:

{tools}

Use the following format:

Question: "the input question you must answer"
Thought: "you should always think about what to do"
Action: "the action to take" --- should be one of [{tool_names}]
Action Input: "the input to the action"
Observation: "the result of the action"
... (this Thought/Action/Action Input/Observation can repeat N times)
Thought: "I now know the final answer"
Final Answer: "the final answer to the original input question"

Begin!

Question: {input}
Thought: {agent_scratchpad}"""

prompt = ChatPrompt(
    system=prompt_template,
    user="{question}",
    agent_class=LangGraphAgent,
)

# Optimize it:
optimizer = FewShotBayesianOptimizer(
    model="openai/gpt-4o",
    min_examples=3,
    max_examples=8,
    n_threads=16,
    seed=42,
)
optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_trials=10,
    n_samples=50,
)

optimization_result.display()
