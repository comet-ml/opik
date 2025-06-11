from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import ChatPrompt, FewShotBayesianOptimizer
from opik_optimizer.datasets import hotpot_300

hot_pot_dataset = hotpot_300()

# For chat prompts instruction doesn't need to contain input parameters from dataset examples.
prompt  = ChatPrompt(
    messages= [
        {"role": "system", "content": "Answer the question."},
        {"role": "user", "content": "{question}"}
    ]
)
project_name = "optimize-few-shot-bayesian-hotpot"

optimizer = FewShotBayesianOptimizer(
    model="gpt-4o-mini",
    project_name=project_name,
    min_examples=3,
    max_examples=8,
    n_threads=16,
    seed=42,
)

def levenshtein_ratio(dataset_item, llm_output):
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=hot_pot_dataset,
    metric=levenshtein_ratio,
    n_trials=10,
    n_samples=150,
)

result.display()
