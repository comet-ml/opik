"""
This script demonstrates how to use the n_samples parameter for multiple completions pass@k style optimization.
Pass@k style optimization is useful when you want to optimize for the best possible output, but you are not
concerned with the consistency of the output.

This is done by passing the n parameter to the model_parameters of the ChatPrompt.
- The optimizer will then generate n (e.g. n=3) completions for each prompt and select the best one.
- The best one will be used for optimization feedback.
- The optimizer will then repeat this process for the max_trials number of times.
- The best prompt will be returned.

Additionally, we will use the max_logprob selection policy to select the best completion.
This policy will select the completion with the highest average logprob.
This is done by passing the selection_policy parameter to the model_parameters of the ChatPrompt.
We do need to enable logprobs in the model_parameters to use this selection policy along with the top_logprobs parameter.

There are other selection policies that can be used, such as:
- best_by_metric: select the completion with the highest score.
- first: select the first completion.
- concat: concatenate all completions into a single string.
- random: select a random completion.
"""

from __future__ import annotations

from opik_optimizer import ChatPrompt, MetaPromptOptimizer
from opik_optimizer.datasets import hotpot


# 1. Define the metric to optimize
def exact_match_metric(dataset_item: dict[str, str], llm_output: str) -> float:
    """Score outputs with exact-match accuracy."""
    expected = dataset_item["answer"].strip()
    return 1.0 if expected == llm_output.strip() else 0.0


# 2. Load dataset (hotpot subset for a quick run).
dataset = hotpot(count=50)

# 3. Define the prompt to optimize
system_prompt = "Answer the question succinctly."

# 4. Define the chat prompt and model parameters
prompt = ChatPrompt(
    system=system_prompt,
    user="{question}",
    model="openai/gpt-4.1",
    model_parameters={
        # Number of samples to generate
        "n": 3,
        "temperature": 0.7,
        # Opik evaluation selection policy to use
        "selection_policy": "max_logprob",
        # Logprob support is provider-specific; fallback is best_by_metric.
        "logprobs": True,
        "top_logprobs": 1,
    },
)

# 5. Define the optimizer
optimizer = MetaPromptOptimizer(
    model="openai/gpt-4.1",
    n_threads=1,
)

# 6. Optimize the prompt
result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=exact_match_metric,
    n_threads=1,
    max_trials=1,
)

# 7. Print the best score
print(f"Best score: {result.score}")
