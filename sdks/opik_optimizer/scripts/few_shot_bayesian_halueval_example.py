import opik
import pandas as pd
from opik.evaluation import metrics
from opik.evaluation.metrics import score_result
from opik_optimizer import FewShotBayesianOptimizer


def ensure_halueval_dataset_created() -> None:
    client = opik.Opik()
    try:
        client.get_dataset("halu_eval")
        return
    except Exception:
        pass

    df = pd.read_parquet(
        "hf://datasets/pminervini/HaluEval/general/data-00000-of-00001.parquet"
    )
    df = df.sample(n=300, random_state=42)

    dataset_records = [
        {
            "input": x["user_query"],
            "llm_output": x["chatgpt_response"],
            "expected_hallucination_label": x["hallucination"],
        }
        for x in df.to_dict(orient="records")
    ]

    
    dataset = client.create_dataset(
        name="halu_eval",
        description="Dataset for hallucination detection",
    )

    dataset.insert(dataset_records)


class HaluEvalObjective(metrics.BaseMetric):
    def __init__(self, name: str = "halu_eval_objective", track: bool = True):
        super().__init__(name=name, track=track)

    def score(self, ground_truth: str, output: str, **ignored_kwargs) -> score_result.ScoreResult:
        ground_truth = ground_truth.lower()
        output = output.lower()

        true_negative_weight = 1
        true_positive_weight = 2.5

        if ground_truth == "no":
            return score_result.ScoreResult(
                value=(1 if ground_truth == output else 0) * true_negative_weight,
                name=self.name,
            )

        return score_result.ScoreResult(
            value=(1 if ground_truth == output else 0) * true_positive_weight,
            name=self.name,
        )

halu_eval_accuracy = HaluEvalObjective()
ensure_halueval_dataset_created()

prompt = """
You are an expert in LLM hallucination detection. You will be given a user input, an llm output,
and a couple of examples to help you make a decision.

Answer with just one word: 'yes' if there is a hallucination and 'no' if there is not.

The user input: 
{{input}}

The llm output: 
{{llm_output}}
"""

optimizer = FewShotBayesianOptimizer(
    model="gpt-4o-mini",
    project_name="optimize-few-shot-bayesian-halueval",
    max_examples=8,
)

result = optimizer.optimize_prompt(
    dataset="halu_eval",
    metric=halu_eval_accuracy,
    prompt=prompt,
    demo_examples_keys_mapping={
        "User input": "input",
        "LLM output": "llm_output",
        "Expected hallucination label": "expected_hallucination_label",
    },
    num_threads=12,
    n_trials=10,
    scoring_key_mapping={
        "output": "output",
        "ground_truth": "expected_hallucination_label",
    },
    train_ratio=0.3,
)

print(result.prompt)
