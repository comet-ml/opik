from opik.evaluation.metrics import AnswerRelevance
from opik_optimizer.few_shot_bayesian_optimizer import FewShotBayesianOptimizer
from opik_optimizer.demo import get_or_create_dataset

optimizer = FewShotBayesianOptimizer(
    model="gpt-4o-mini",
    project_name="optimize-few-shot-bayesian-hotpot",
    max_examples=7,
)

metric = AnswerRelevance(model="gpt-4o")
prompt = """
You are an expert in answering questions. 
Answer the question with a short phrase. 
The question: 
{{question}}
"""

hot_pot_dataset = get_or_create_dataset("hotpot-300")
best_prompt = optimizer.optimize_prompt(
    dataset=hot_pot_dataset,
    metric=metric,
    prompt=prompt,
    demo_examples_keys_mapping={
        "Question": "question",
        "Answer": "answer",
    },
    num_threads=12,
    n_trials=10,
    scoring_key_mapping={
        "output": "output",  # hard-coded predictor output key
        "context": lambda dataset_item: [dataset_item["answer"]],
        "input": "question",
    },
    train_ratio=0.5,
)

print(best_prompt)
