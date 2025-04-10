# Run the dataset generation script first. In this folder:
# from hotpot_dataset_generation import make_hotpot_qa
# make_hotpot_qa()

from opik.evaluation.metrics import AnswerRelevance
from opik_optimizer import FewShotBayesianOptimizer

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
best_prompt = optimizer.optimize_prompt(
    dataset="hotpot-300",
    metric=metric,
    prompt=prompt,
    demo_examples_keys_mapping={
        "Question": "question",
        "Answer": "answer",
    },
    num_threads=8,
    n_trials=10,
    scoring_key_mapping={
        "output": "output",  # hard-coded predictor output key
        "context": lambda dataset_item: [dataset_item["answer"]],
        "input": "question",
    },
    train_ratio=0.5,
)

print(best_prompt)
