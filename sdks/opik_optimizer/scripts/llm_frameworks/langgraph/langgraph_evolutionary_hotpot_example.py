from typing import Any

from opik_optimizer import (
    ChatPrompt,
    EvolutionaryOptimizer,
)
from opik_optimizer.datasets import hotpot

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult

from langgraph_agent import LangGraphAgent

dataset = hotpot(count=25)


def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


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
)

# Optimize it:
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
    agent_class=LangGraphAgent,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_samples=5,
)

optimization_result.display()
