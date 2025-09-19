from typing import Dict, Any

from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import (
    ChatPrompt,
    FewShotBayesianOptimizer,
    OptimizableAgent,
)
from opik_optimizer.datasets import hotpot_300
from opik_optimizer.utils import search_wikipedia

# NOTE: functions are automatically tracked in the ChatPrompt


def levenshtein_ratio(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


dataset = hotpot_300()

optimizer = FewShotBayesianOptimizer(
    model="openai/gpt-4o-mini",
    min_examples=3,
    max_examples=8,
    n_threads=1,
    seed=42,
)

system_prompt = """
Answer the question with a direct phrase. Use the tool `search_wikipedia`
if you need it. Make sure you consider the results before answering the
question.
"""

tool_prompt = "This function is used to search wikipedia abstracts."


def make_chat_prompt(system_prompt: str, tool_prompt: str) -> ChatPrompt:
    prompt = ChatPrompt(
        system=system_prompt,
        user="{question}",
        # Values for the ChatPrompt LLM
        tools=[
            {
                "type": "function",
                "function": {
                    "name": "search_wikipedia",
                    "description": tool_prompt,
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "query": {
                                "type": "string",
                                "description": "The query parameter is the term or phrase to search for.",
                            },
                        },
                        "required": ["query"],
                    },
                },
            },
        ],
        function_map={"search_wikipedia": search_wikipedia},
    )
    return prompt


# ---------------------------------------
# Step 1:
# Here we set the initial prompt to use the tool_prompt
# as the prompt to be optimized:
prompt = make_chat_prompt(tool_prompt, tool_prompt)
# ---------------------------------------


class LiteLLMAgent(OptimizableAgent):
    project_name = "litellm-agent"
    model = "openai/gpt-4o-mini"

    def init_agent(self, prompt: "ChatPrompt") -> None:
        """Initialize the agent with the provided configuration."""
        # ---------------------------------------
        # Step 2:
        # We swap in the system prompt and updated tool prompt
        # to actually to the evaluation:
        tool_prompt = prompt.messages[0]["content"]
        self.prompt = make_chat_prompt(system_prompt, tool_prompt)
        # ---------------------------------------


optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    agent_class=LiteLLMAgent,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_trials=10,
    n_samples=50,
)
optimization_result.display()
