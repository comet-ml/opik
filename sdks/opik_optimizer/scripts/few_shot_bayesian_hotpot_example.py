# run the dataset generation script first

from opik.evaluation.metrics import AnswerRelevance
from opik_optimizer import FewShotBayesianOptimizer

optimizer = FewShotBayesianOptimizer(
    model="gpt-4o",
    project_name="few-shot-optimizers",
    max_examples=7,
)

metric = AnswerRelevance(model="gpt-4o")

result = optimizer.optimize_prompt(
    dataset="hotpot-300",
    metric=metric,
    prompt="You are an expert in answering questions. Answer the question with a short phrase. The question: \n{{question}}",
    input_key="question",
    output_key="answer",
    num_threads=8,
    n_trials=10,
    scoring_key_mapping={
        "output": "output",  # hard-coded predictor output key
        "context": lambda dataset_item: [dataset_item["answer"]],
        "input": "question",
    },
    train_ratio=0.5,
)

print(result)
