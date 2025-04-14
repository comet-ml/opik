from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer.few_shot_bayesian_optimizer import FewShotBayesianOptimizer
from opik_optimizer.demo import get_or_create_dataset

optimizer = FewShotBayesianOptimizer(
    model="gpt-4o-mini",
    project_name="optimize-few-shot-bayesian-hotpot",
    min_examples=2,
    max_examples=8,
)

metric = LevenshteinRatio()
prompt = """
Answer the question. 
The question: 
{{question}}
"""

hot_pot_dataset = get_or_create_dataset("hotpot-300")

metric_inputs_from_dataset_columns_mapping = {
    "reference": "answer",
}
metric_inputs_from_predictor_output_mapping = {
    "output": "evaluated_output",  # evaluated_output is the reserved name for predictor output string
}


initial_score = optimizer.evaluate_prompt(
    dataset=hot_pot_dataset,
    metric=metric,
    prompt=prompt,
    metric_inputs_from_dataset_columns_mapping=metric_inputs_from_dataset_columns_mapping,
    metric_inputs_from_predictor_output_mapping=metric_inputs_from_predictor_output_mapping,
    num_threads=16,
)

print("Initial score:", initial_score)

result = optimizer.optimize_prompt(
    dataset=hot_pot_dataset,
    metric=metric,
    prompt=prompt,
    few_shot_examples_inputs_from_dataset_columns_mapping={
        "Question": "question",
        "Answer": "answer",
    },
    metric_inputs_from_dataset_columns_mapping=metric_inputs_from_dataset_columns_mapping,
    metric_inputs_from_predictor_output_mapping=metric_inputs_from_predictor_output_mapping,
    num_threads=16,
    n_trials=10,
    train_ratio=0.4,
)

print(result.prompt)

final_score = optimizer.evaluate_prompt(
    dataset=hot_pot_dataset,
    metric=metric,
    prompt=result.prompt,
    metric_inputs_from_dataset_columns_mapping=metric_inputs_from_dataset_columns_mapping,
    metric_inputs_from_predictor_output_mapping=metric_inputs_from_predictor_output_mapping,
    num_threads=16,
)

print("Final score:", final_score)