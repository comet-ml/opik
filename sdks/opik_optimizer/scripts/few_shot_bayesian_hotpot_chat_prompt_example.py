from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer.few_shot_bayesian_optimizer import FewShotBayesianOptimizer
from opik_optimizer.demo import get_or_create_dataset

from opik_optimizer import (
    OptimizationConfig,
    MetricConfig,
    PromptTaskConfig,
    from_dataset_field,
    from_llm_response_text,
)

hot_pot_dataset = get_or_create_dataset("hotpot-300")

# For chat prompts instruction doesn't need to contain input parameters from dataset examples.
prompt_instruction = """
Answer the question.
"""
project_name = "optimize-few-shot-bayesian-hotpot"
initial_prompt_no_examples = [
    {"role": "system", "content": prompt_instruction},
    {"role": "user", "content": "{{question}}"},
]

optimizer = FewShotBayesianOptimizer(
    model="gpt-4o-mini",
    project_name=project_name,
    min_examples=3,
    max_examples=8,
    n_threads=6,
    seed=42,
)

optimization_config = OptimizationConfig(
    dataset=hot_pot_dataset,
    objective=MetricConfig(
        metric=LevenshteinRatio(project_name=project_name),
        inputs={
            "output": from_llm_response_text(),
            "reference": from_dataset_field(name="answer"),
        },
    ),
    task=PromptTaskConfig(
        instruction_prompt=prompt_instruction,
        input_dataset_fields=["question"],
        output_dataset_field="answer",
        use_chat_prompt=True,
    ),
)

initial_score = optimizer.evaluate_prompt(
    dataset=hot_pot_dataset,
    metric_config=optimization_config.objective,
    prompt=initial_prompt_no_examples,
    num_test=100,
)

print("Initial prompt:", initial_prompt_no_examples)
print("Initial score:", initial_score)

result = optimizer.optimize_prompt(
    optimization_config,
    n_trials=5,
    num_test=100,
)

print("Final prompt:", result.prompt)

final_score = optimizer.evaluate_prompt(
    dataset=hot_pot_dataset,
    metric_config=optimization_config.objective,
    prompt=result.prompt,
    num_test=100,
)

print("Final score:", final_score)
