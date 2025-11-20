from typing import Any

from opik.evaluation.metrics import LevenshteinRatio
from opik.evaluation.metrics.score_result import ScoreResult

from opik_optimizer.datasets import hotpot

from opik_optimizer import (
    ChatPrompt,
    MetaPromptOptimizer,
)

from langgraph_agent import LangGraphAgent


def levenshtein_ratio(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    """
    Calculate the Levenshtein ratio score between dataset answer and LLM output.
    """
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


dataset = hotpot(count=300)

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
optimizer = MetaPromptOptimizer(
    model="openai/gpt-4o-mini",  # Using gpt-4o-mini for evaluation for speed
    prompts_per_round=4,  # Number of prompts to generate per round
    n_threads=12,  # Number of threads for parallel evaluation
    model_parameters={
        "temperature": 0.1,  # Lower temperature for more focused responses
        "max_completion_tokens": 5000,  # Maximum tokens for model completion
    },
)

optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    agent_class=LangGraphAgent,
    dataset=dataset,
    metric=levenshtein_ratio,
    max_trials=12,  # Number of total trials (max_rounds * num_prompts_per_round)
    n_samples=10,
)
optimization_result.display()
