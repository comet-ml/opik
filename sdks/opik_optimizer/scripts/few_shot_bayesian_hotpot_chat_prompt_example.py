from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer import FewShotBayesianOptimizer
from opik_optimizer.demo import get_or_create_dataset

from opik_optimizer import (
    MetricConfig,
    TaskConfig,
    from_dataset_field,
    from_llm_response_text,
)

hot_pot_dataset = get_or_create_dataset("hotpot-300")

# For chat prompts instruction doesn't need to contain input parameters from dataset examples.
prompt_instruction = """
Answer the question.
"""
project_name = "optimize-few-shot-bayesian-hotpot"

optimizer = FewShotBayesianOptimizer(
    model="gpt-4o-mini",
    project_name=project_name,
    min_examples=3,
    max_examples=8,
    n_threads=16,
    seed=42,
)

metric_config = MetricConfig(
    metric=LevenshteinRatio(project_name=project_name),
    inputs={
        "output": from_llm_response_text(),
        "reference": from_dataset_field(name="answer"),
    },
)

task_config = TaskConfig(
    instruction_prompt=prompt_instruction,
    input_dataset_fields=["question"],
    output_dataset_field="answer",
    use_chat_prompt=True,
)

result = optimizer.optimize_prompt(
    dataset=hot_pot_dataset,
    metric_config=metric_config,
    task_config=task_config,
    n_trials=10,
    n_samples=150,
)

result.display()
